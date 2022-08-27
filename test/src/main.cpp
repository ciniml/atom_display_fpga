
#if 0
# define LGFX_USE_V1
# include <LovyanGFX.hpp>
# include <lgfx_user/M5AtomDisplay.hpp>
#else
//# include <M5AtomDisplay.h>
# include <M5SPI2HDMI.h>
#endif
//static M5AtomDisplay display(1280, 720);
static M5SPI2HDMI display(1280, 720);

void setup(void)
{
  Serial.begin(115200);
  Serial.println("Configure FPGA...");
  //delay(10000);
  Serial.println("Start...");
  display.init();
  for (int i = 0; i < 64; ++i)
  {
    display.drawCircle(128, 128, i, rand());
    display.drawCircle(384, 128, i, rand());
    display.drawCircle(128, 384, i, rand());
    display.drawCircle(384, 384, i, rand());
  }

  delay(2000);
  display.copyRect( 64, 0, 128, 128,  64, 64); // 上にコピー
  display.copyRect(384, 64, 128, 128, 320, 64); // 右にコピー
  display.copyRect(320, 384, 128, 128, 320, 320); // 下にコピー
  display.copyRect(0, 320, 128, 128, 64, 320); // 左にコピー
  delay(2000);
}

static uint32_t count = 0;
void loop(void)
{
  Serial.println(count++);
  display.copyRect( 64, 63, 128, 128,  64, 64); // 上にコピー
  display.copyRect(321, 64, 128, 128, 320, 64); // 右にコピー
  display.copyRect(320, 321, 128, 128, 320, 320); // 下にコピー
  display.copyRect(63, 320, 128, 128, 64, 320); // 左にコピー
  delay(100);
}