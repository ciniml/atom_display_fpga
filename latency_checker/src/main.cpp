
#if 0
# define LGFX_USE_V1
# include <LovyanGFX.hpp>
# include <lgfx_user/M5AtomDisplay.hpp>
#else
# include <M5AtomDisplay.h>
#endif
#include <driver/mcpwm.h>

static M5AtomDisplay display(1920, 1080);

static constexpr const int PIN_SCREEN_BLINK = 22;
static constexpr const int PIN_SENSOR = 32;
static constexpr const int CAPTURE_CLOCK_HZ = 80000000; // APB clock

static QueueHandle_t capture_queue;

enum class CaptureTarget : uint32_t
{
  ScreenBlink = 0,
  Sensor,
};
typedef struct {
  cap_event_data_t cap_event_data;
  CaptureTarget target;
} capture_queue_t;

static bool capture_callback(mcpwm_unit_t mcpwm, mcpwm_capture_channel_id_t cap_channel, const cap_event_data_t *edata, void *user_data) {
  capture_queue_t item = {
    .cap_event_data = *edata,
    .target = static_cast<CaptureTarget>(reinterpret_cast<uintptr_t>(user_data)),
  };
  xQueueSendFromISR(capture_queue, &item, nullptr);
  return false;
}

void setup(void)
{
  Serial.begin(115200);
  Serial.println("Configure FPGA...");
  Serial.println("Initializing");
  display.init();
  Serial.println("Initialized");

  // Initialize queue
  capture_queue = xQueueCreate(512, sizeof(capture_queue_t));

  // Configure pins.
  pinMode(PIN_SCREEN_BLINK, INPUT);
  pinMode(PIN_SENSOR, INPUT);

  // 
  ESP_ERROR_CHECK(mcpwm_gpio_init(MCPWM_UNIT_0, MCPWM_CAP_0, PIN_SCREEN_BLINK));
  ESP_ERROR_CHECK(mcpwm_gpio_init(MCPWM_UNIT_0, MCPWM_CAP_1, PIN_SENSOR));
  {
    mcpwm_capture_config_t config = {
      .cap_edge = MCPWM_BOTH_EDGE,
      .cap_prescale = 1,
      .capture_cb = capture_callback,
      .user_data = reinterpret_cast<void*>(CaptureTarget::ScreenBlink),
    };
    ESP_ERROR_CHECK(mcpwm_capture_enable_channel(MCPWM_UNIT_0, MCPWM_SELECT_CAP0, &config));
  }
  {
    mcpwm_capture_config_t config = {
      .cap_edge = MCPWM_BOTH_EDGE,
      .cap_prescale = 1,
      .capture_cb = capture_callback,
      .user_data = reinterpret_cast<void*>(CaptureTarget::Sensor),
    };
    ESP_ERROR_CHECK(mcpwm_capture_enable_channel(MCPWM_UNIT_0, MCPWM_SELECT_CAP1, &config));
  }
}

static uint32_t count = 0;
enum class State {
  Unknown,
  High,
  Low,
};

static volatile uint32_t last_blink_edge_counter = 0;
static volatile uint32_t last_sensor_edge_counter = 0;
static volatile State blink_state = State::Unknown;
static volatile State sensor_state = State::Unknown;

void loop(void)
{
  capture_queue_t item;
  while( xQueueReceive(capture_queue, &item, pdMS_TO_TICKS(100)) == pdTRUE ) {
    if( item.target == CaptureTarget::ScreenBlink ) {
      uint32_t elapsed = item.cap_event_data.cap_value - last_blink_edge_counter;
      // if( blink_state != State::Unknown ) {
      //   Serial.printf("interval: %f[s]\n", float(elapsed)/float(CAPTURE_CLOCK_HZ));
      // }
      blink_state = item.cap_event_data.cap_edge == MCPWM_POS_EDGE ? State::High : State::Low;
      last_blink_edge_counter = item.cap_event_data.cap_value;
    }
    else {
      if( item.cap_event_data.cap_value - last_sensor_edge_counter >= CAPTURE_CLOCK_HZ/8 ) {
        uint32_t diff = item.cap_event_data.cap_value - last_blink_edge_counter;
        float diff_s = float(diff)/float(CAPTURE_CLOCK_HZ);
        if( blink_state == State::High && item.cap_event_data.cap_edge == MCPWM_POS_EDGE ) {
          Serial.printf("B->W: %f[s]\n", diff_s);
        }
        else if( blink_state == State::Low && item.cap_event_data.cap_edge == MCPWM_NEG_EDGE ) {
          Serial.printf("W->B: %f[s]\n", diff_s);
        }
        sensor_state = item.cap_event_data.cap_edge == MCPWM_POS_EDGE ? State::High : State::Low;
        last_sensor_edge_counter = item.cap_event_data.cap_value;
      }
    }
  }
  
  //Serial.printf("BLINK: %s SENSOR: %s\n", digitalRead(PIN_SCREEN_BLINK) ? "H" : "L", digitalRead(PIN_SENSOR) ? "H" : "L");
  //delay(100);
}