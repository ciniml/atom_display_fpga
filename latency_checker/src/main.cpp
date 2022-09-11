
#if 0
# define LGFX_USE_V1
# include <LovyanGFX.hpp>
# include <lgfx_user/M5AtomDisplay.hpp>
#else
# include <M5AtomDisplay.h>
#endif
#include <driver/mcpwm.h>
#include <driver/timer.h>
#include <hal/adc_ll.h>

static M5AtomDisplay display(1920, 1080);

static constexpr const int PIN_BUTTON = 39;
static constexpr const gpio_num_t PIN_SCREEN_BLINK = GPIO_NUM_22;
static constexpr const gpio_num_t PIN_SENSOR = GPIO_NUM_32;
static constexpr const adc1_channel_t ADC_CHANNEL_SENSOR = ADC1_CHANNEL_4; // GPIO32 = ADC1 channel 4
#define TIMERG TIMERG0
static constexpr const timer_group_t TIMER_GROUP = TIMER_GROUP_0;
static constexpr const timer_idx_t TIMER_INDEX = TIMER_0;
static constexpr const uint32_t TIMER_CLOCK_DIVIDER = 2;  // Minimum divider = APB/2
static constexpr const uint64_t TIMER_CLOCK_HZ = 80000000u/TIMER_CLOCK_DIVIDER; // Minimum divider = APB/2
static constexpr const uint64_t TIMER_COUNTER_PERIOD = TIMER_CLOCK_HZ/50000u;   // Frequency = 50[kHz]
static uint16_t SENSOR_THRESHOLD_H_I = 190;
static uint16_t SENSOR_THRESHOLD_I_L = 20;
static uint16_t SENSOR_THRESHOLD_L_I = 30;
static uint16_t SENSOR_THRESHOLD_I_H = 200;

static uint32_t count = 0;
enum class State {
  Unknown,
  High,
  Low,
  IntermediateHL,
  IntermediateLH,
};

static const char* state_to_string(State state) {
  switch(state) {
    case State::High:           return "H ";
    case State::Low:            return "L ";
    case State::IntermediateHL: return "HL";
    case State::IntermediateLH: return "LH";
    default: return "U ";
  }
}

static volatile uint64_t last_blink_timestamp[2] = {0, 0};
static volatile uint64_t last_sensor_timestamp[2] = {0, 0};;
static volatile State blink_state = State::Unknown;
static volatile State sensor_state = State::Unknown;

enum class CaptureTarget : uint32_t
{
  ScreenBlink = 0,
  Sensor,
};

static IRAM_ATTR void gpio_isr_handler(void* data)
{
  int level = gpio_get_level(PIN_SCREEN_BLINK);
  last_blink_timestamp[level ? 0 : 1] = esp_timer_get_time();
  blink_state = level ? State::High : State::Low;
}

static constexpr const int ADC_AVERAGE_POINTS = 4;
static volatile uint32_t adc_sum = 0;
static volatile uint16_t adc_average_buffer[ADC_AVERAGE_POINTS] = {0};
static volatile uint32_t adc_average_index = 0;
static IRAM_ATTR void hardware_timer_isr(void* arg)
{
    if( TIMERG.int_raw.t0 == 0 ) {
        return;
    }
    // Clear interrupt flag.
    TIMERG.int_clr_timers.t0 = 1;
    // Clear timer counter
    TIMERG.hw_timer[0].load_high = 0;
    TIMERG.hw_timer[0].load_low = 0;
    TIMERG.hw_timer[0].reload = 0;  // set counter to zero.

    int64_t timestamp = esp_timer_get_time();

    // Re-enable timer alarm
    TIMERG.hw_timer[0].alarm_high = (uint32_t) (TIMER_COUNTER_PERIOD >> 32);
    TIMERG.hw_timer[0].alarm_low = (uint32_t) TIMER_COUNTER_PERIOD;
    TIMERG.hw_timer[0].config.alarm_en = TIMER_ALARM_EN;

    // Sample ADC1
    bool do_notify = false;
    if( adc_ll_rtc_convert_is_done(ADC_NUM_1) ) {
      uint32_t adc_value = adc_ll_rtc_get_convert_value(ADC_NUM_1);
      adc_sum = adc_sum + adc_value - adc_average_buffer[adc_average_index];
      adc_average_buffer[adc_average_index] = adc_value;
      adc_average_index = (adc_average_index < ADC_AVERAGE_POINTS - 1) ? adc_average_index + 1 : 0;

      // Update sensor state.
      uint32_t adc_average = adc_sum / ADC_AVERAGE_POINTS;
      State new_sensor_state = sensor_state;
      switch(sensor_state) {
        case State::Unknown:
          if( adc_average <= SENSOR_THRESHOLD_I_L ) {
            new_sensor_state = State::Low;
          }
          else if( adc_average >= SENSOR_THRESHOLD_I_H ) {
            new_sensor_state = State::High;
          }
          break;
        case State::High:
          if( adc_average <= SENSOR_THRESHOLD_H_I ) {
            new_sensor_state = State::IntermediateHL;
          }
          break;
        case State::IntermediateHL:
          if( adc_average <= SENSOR_THRESHOLD_I_L ) {
            new_sensor_state = State::Low;
          }
          break;
        case State::Low:
          if( adc_average >= SENSOR_THRESHOLD_L_I ) {
            new_sensor_state = State::IntermediateLH;
          }
          break;
        case State::IntermediateLH:
          if( adc_average >= SENSOR_THRESHOLD_I_H ) {
            new_sensor_state = State::High;
          }
          break;
        
      }
      if( sensor_state == State::Low && new_sensor_state == State::IntermediateLH ) {// Rising edge
        last_sensor_timestamp[0] = esp_timer_get_time();
        do_notify = true;
      }
      if( sensor_state == State::High && new_sensor_state == State::IntermediateHL ) {// Falling edge
        last_sensor_timestamp[1] = esp_timer_get_time();
        do_notify = true;
      }
      sensor_state = new_sensor_state;
    }
    adc_ll_set_controller(ADC_NUM_1, ADC_LL_CTRL_RTC);
    adc_ll_rtc_enable_channel(ADC_NUM_1, (int)ADC_CHANNEL_SENSOR);
    adc_ll_rtc_start_convert(ADC_NUM_1, (int)ADC_CHANNEL_SENSOR);

    // Notify
    if( do_notify ) {
      TaskHandle_t task = (TaskHandle_t)arg;
      xTaskNotifyFromISR(task, 1, eSetBits, NULL);
      portYIELD_FROM_ISR();   // In FreeRTOS, the task currently running is not yielded even if a notification is sent to another task. Thus we have to yield the current task explicitly by calling portYIELD_FROM_ISR().
    }
}

void setup(void)
{
  Serial.begin(115200);
  Serial.println("Initializing");
  display.init();
  Serial.println("Initialized");

  // Configure pins.
  {
    gpio_config_t config = {
      .pin_bit_mask = (uint64_t(1) << PIN_SCREEN_BLINK),
      .mode = GPIO_MODE_INPUT,
      .pull_up_en = GPIO_PULLUP_DISABLE,
      .pull_down_en = GPIO_PULLDOWN_DISABLE,
      .intr_type = GPIO_INTR_ANYEDGE,
    };
    ESP_ERROR_CHECK(gpio_config(&config));
    config.pin_bit_mask = (uint64_t(1) << PIN_SENSOR);
    config.intr_type = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&config));
  }

  // Configure ADC1
  adc_power_acquire();
  {
    ESP_ERROR_CHECK(adc_set_clk_div(80/5)); // APB = 80[MHz], Mac ADC clock is 5[MHz]
    ESP_ERROR_CHECK(adc1_config_width(ADC_WIDTH_BIT_12));
    ESP_ERROR_CHECK(adc1_config_channel_atten(ADC_CHANNEL_SENSOR, ADC_ATTEN_DB_11));
  }

  // Configure ADC sampling timer.
    timer_config_t timer_config = {
      .alarm_en = TIMER_ALARM_EN,
      .counter_en = TIMER_PAUSE,
      .intr_type = TIMER_INTR_LEVEL,
      .counter_dir = TIMER_COUNT_UP,
      .auto_reload = TIMER_AUTORELOAD_EN,
      .divider = TIMER_CLOCK_DIVIDER,
  };
  ESP_ERROR_CHECK(timer_init(TIMER_GROUP, TIMER_INDEX, &timer_config));
  ESP_ERROR_CHECK(timer_set_counter_value(TIMER_GROUP, TIMER_INDEX, 0));
  ESP_ERROR_CHECK(timer_set_alarm_value(TIMER_GROUP, TIMER_INDEX, TIMER_COUNTER_PERIOD));
  ESP_ERROR_CHECK(timer_isr_register(TIMER_GROUP, TIMER_INDEX, hardware_timer_isr, xTaskGetCurrentTaskHandle(), ESP_INTR_FLAG_IRAM | ESP_INTR_FLAG_LEVEL3, NULL));
  ESP_ERROR_CHECK(timer_start(TIMER_GROUP, TIMER_INDEX));
  
  // Install ISR service and handler
  ESP_ERROR_CHECK(gpio_install_isr_service(ESP_INTR_FLAG_IRAM));
  ESP_ERROR_CHECK(gpio_isr_handler_add(PIN_SCREEN_BLINK, gpio_isr_handler, nullptr));

  // Button
  pinMode(PIN_BUTTON, INPUT_PULLUP);
}

void loop(void)
{
  static uint16_t calib_adc_max = 0;
  static uint16_t calib_adc_min = 0;
  static bool is_calibrating = false;

  uint16_t adc_average = adc_sum / ADC_AVERAGE_POINTS;
  bool is_button_pressed = digitalRead(PIN_BUTTON) == 0;

  if( !is_calibrating && is_button_pressed ) {
    // Start calibration.
    is_calibrating = true;
    calib_adc_max = 0;
    calib_adc_min = 511;
    Serial.printf("Start calibration");
  } else if( is_calibrating && !is_button_pressed ) {
    is_calibrating = false;
    uint32_t range = calib_adc_max - calib_adc_min;
    SENSOR_THRESHOLD_H_I = range*80/100 + calib_adc_min;
    SENSOR_THRESHOLD_I_L = range* 5/100 + calib_adc_min;
    SENSOR_THRESHOLD_L_I = range*10/100 + calib_adc_min;
    SENSOR_THRESHOLD_I_H = range*95/100 + calib_adc_min;
    Serial.printf("End calibration %d %d %d %d\n"
        , SENSOR_THRESHOLD_H_I
        , SENSOR_THRESHOLD_I_L
        , SENSOR_THRESHOLD_L_I
        , SENSOR_THRESHOLD_I_H
    );
  }
  if( is_calibrating ) {
    if( calib_adc_max < adc_average ) {
      calib_adc_max = adc_average;
    }
    if( calib_adc_min > adc_average ) {
      calib_adc_min = adc_average;
    }
  }

  //Serial.printf("BLINK: %s SENSOR: %s\n", digitalRead(PIN_SCREEN_BLINK) ? "H" : "L", digitalRead(PIN_SENSOR) ? "H" : "L");
  if( xTaskNotifyWait(0, 1, nullptr, pdMS_TO_TICKS(1)) == pdTRUE ) {
    int64_t diff_b_to_w = last_sensor_timestamp[0] - last_blink_timestamp[0];
    int64_t diff_w_to_b = last_sensor_timestamp[1] - last_blink_timestamp[1];
    Serial.printf("BLINK: %s SENSOR: %s (%d) B->W %lld, W->B %lld\n", state_to_string(blink_state), state_to_string(sensor_state), adc_average, diff_b_to_w, diff_w_to_b);
    Serial.printf("%lld-%lld, %lld-%lld\n", last_sensor_timestamp[0], last_blink_timestamp[0], last_sensor_timestamp[1], last_blink_timestamp[1]);
  }  
}