#if 0
# define LGFX_USE_V1
# include <LovyanGFX.hpp>
# include <lgfx_user/M5AtomDisplay.hpp>
#elif defined(USE_MODULE_DISPLAY)
# include <M5ModuleDisplay.h>
#else
# include <M5AtomDisplay.h>
#endif

#ifndef USE_MODULE_DISPLAY
static M5AtomDisplay display(1280, 720);
//static M5AtomDisplay display(640, 360, 60, 1280, 720);
//static M5AtomDisplay display(1024, 768, 60, 1024, 768);
//static M5AtomDisplay display(960, 540, 60);
//static M5AtomDisplay display(320, 180, 60, 960, 1080, 0, 0, 148500000U);
#else
static M5ModuleDisplay display(1280, 720);
//static M5ModuleDisplay display = { 1024, 768, 60, 1024, 768 };
//static M5ModuleDisplay display = { 640, 480, 60, 640, 480 };
//static M5ModuleDisplay display = { 1920, 1080, 24, 1920, 1080, };
//static M5ModuleDisplay display(640, 480);
//static M5ModuleDisplay display(960, 540, 60, 960, 1080, 1, 2, 148500000U);
//static M5ModuleDisplay display(320, 180, 60, 960, 1080, 0, 0, 148500000U);

#endif

// void setup(void)
// {
//   Serial.begin(115200);
//   Serial.println("Configure FPGA...");
//   //delay(10000);
//   Serial.println("Start...");
//   display.init();
//   // for (int i = 0; i < 64; ++i)
//   // {
//   //   display.drawCircle(128, 128, i, rand());
//   //   display.drawCircle(384, 128, i, rand());
//   //   display.drawCircle(128, 384, i, rand());
//   //   display.drawCircle(384, 384, i, rand());
//   // }

//   // delay(2000);
//   // display.copyRect( 64, 0, 128, 128,  64, 64); // 上にコピー
//   // display.copyRect(384, 64, 128, 128, 320, 64); // 右にコピー
//   // display.copyRect(320, 384, 128, 128, 320, 320); // 下にコピー
//   // display.copyRect(0, 320, 128, 128, 64, 320); // 左にコピー
//   // delay(2000);
// }

// static uint32_t count = 0;
// void loop(void)
// {
//   //Serial.println(count++);
//   // display.copyRect( 64, 63, 128, 128,  64, 64); // 上にコピー
//   // display.copyRect(321, 64, 128, 128, 320, 64); // 右にコピー
//   // display.copyRect(320, 321, 128, 128, 320, 320); // 下にコピー
//   // display.copyRect(63, 320, 128, 128, 64, 320); // 左にコピー
//   int x = rand()%640;
//   int y = rand()%400;
//   int w = rand()%640;
//   int h = rand()%400;
//   int c = rand();
//   display.fillRect(x, y, w, h, c);
//   //delay(100);
// }

M5Canvas cv;

void setup(void)
{
  log_e("Initializing Display...");
  if( !display.init() ) {
    log_e("failed.");
    while(true) delay(1);
  } else {
    display.setTextScroll(true);
    log_e("done.");
  }

  cv.createSprite(2,2);
  cv.fillScreen(TFT_YELLOW);
}

// void loop(void)
// {
//   static int i = 0;
//   display.printf("count:%d ", i);
//   //Serial.printf("count:%d\n", i);
//   ++i;
//   delay(1);
// }

// void loop(void)
// {
//   delay(500);
//   // 動作が停止してない事を画面上で確認するために左上隅に矩形をランダムな色で描く
//   display.fillRect(0,0,16,16,rand());
//   delay(500);

//   // 上記の矩形の右下隅付近にcvの内容を描画する。
//   static int i = 16;
//   cv.pushSprite(&display, i, i);
//   // 毎回右下方向に移動する。
//   ++i;
// }

static uint32_t count = 0;
void loop(void)
{
  Serial.println(count++);
  // display.copyRect( 64, 63, 128, 128,  64, 64); // 上にコピー
  // display.copyRect(321, 64, 128, 128, 320, 64); // 右にコピー
  // display.copyRect(320, 321, 128, 128, 320, 320); // 下にコピー
  // display.copyRect(63, 320, 128, 128, 64, 320); // 左にコピー
  int x = rand()%display.width();
  int y = rand()%display.height();
  int w = rand()%display.width();
  int h = rand()%display.height();
  int c = rand();
  display.fillRect(x, y, w, h, c);
}

// void loop(void)
// {
//   static bool drawn = false;
//   if( drawn ) return;

//   const int multiplier = 4;
//   display.fillRect(0, 0, display.width(), display.height(), TFT_BLACK);
//   for(int x = 0; x < display.width()/multiplier; x++) {
//     display.drawLine(x*multiplier, 0, x*multiplier, display.height(), TFT_WHITE);
//   }
//   display.drawRect(0, 0, display.width(), display.height(), TFT_WHITE);

//   drawn = true;
// }

// 通信内容がコードから読める版
void loop_spicmd(void)
{
  // 動作が停止してない事を画面上で確認するために左上隅に矩形をランダムな色で描く
  display.startWrite();
  display.writeCommand(0x6A);
  display.writeData16(0);
  display.writeData16(0);
  display.writeData16(15);
  display.writeData16(15);
  display.writeData16(rand());
  display.endWrite();
  delay(100);

  int x = 16;
  int w = 4;
  int y = 16;
  int h = 4;

  // 横座標指定コマンド
  display.startWrite();
  display.writeCommand(0x2A);
  display.writeData16(x);
  display.writeData16(x + w - 1);
  display.endWrite();
  delay(100);

  // 縦座標指定コマンド
  display.startWrite();
  display.writeCommand(0x2B);
  display.writeData16(y);
  display.writeData16(y + h - 1);
  display.endWrite();
  delay(100);

  // RGB565ピクセルデータ流しこみコマンド
  display.startWrite();
  display.writeCommand(0x42);
  for (int i = 0; i < w*h; ++i)
  {
    display.writeData16(rand());
  }
  display.endWrite();
  delay(100);
}