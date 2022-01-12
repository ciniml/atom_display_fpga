// SPDX-License-Identifier: CC0-1.0

#include <M5AtomDisplay.h>
M5AtomDisplay display(1280, 720);
// M5AtomDisplay display(1920, 1080, 24);

void setup(void)
{
  display.init();
  display.setRotation(1);
  //display.setColorDepth(24);  // 24bit per pixel color setting
  display.setColorDepth(16);  // 16bit per pixel color setting ( default )
  //display.setColorDepth( 8);  //  8bit per pixel color setting

  display.startWrite();
  for (int y = 0; y < display.height(); ++y)
  {
    for (int x = 0; x < display.width(); ++x)
    {
      display.writePixel(x, y, display.color888(x, x+y, y));
    }
  }
  display.endWrite();

  for (int i = 0; i < 16; ++i)
  {
    int x = rand() % display.width();
    int y = rand() % display.height();
    display.drawCircle(x, y, 16, rand());
  }
}

void loop(void)
{
  display.startWrite();

  static constexpr const char hello_str[] = "Hello ATOM Display !";
  display.setFont(&fonts::Orbitron_Light_32);
  for (int i = -display.textWidth(hello_str); i < display.width(); ++i)
  {
    display.drawString(hello_str, i, (display.height() - display.fontHeight()) >> 1);
  }

  display.endWrite();
}