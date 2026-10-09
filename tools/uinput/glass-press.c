// Presses keys through a temporary virtual input device that identifies as the Fire TV remote
// (vendor 0x0171, product 0x0427), so the remote's key layout applies and Glass sees real key events.
// Used by scripts/key while the Bluetooth remote sleeps (its input node disappears, and an injected
// `input keyevent` Select doesn't click Glass's tiles on Fire OS).
//   glass-press <gap_ms> <code>[:hold_ms] | w<ms> | g ...  (w<ms> waits; g runs $GLASS_PRESS_GUARD with sh and,
//   if it fails, stops there with exit 3: scripts/key's check before a Select, inside the one device session)
// Build: zig cc -target arm-linux-musleabi -static -O2 -o glass-press glass-press.c
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void emit(int fd, int type, int code, int value) {
  struct input_event ev;
  memset(&ev, 0, sizeof ev);
  ev.type = type; ev.code = code; ev.value = value;
  write(fd, &ev, sizeof ev);
}

int main(int argc, char **argv) {
  if (argc < 3) { fprintf(stderr, "usage: glass-press gap_ms code[:hold_ms]...\n"); return 2; }
  int gap = atoi(argv[1]);
  int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
  if (fd < 0) { perror("uinput"); return 1; }
  ioctl(fd, UI_SET_EVBIT, EV_KEY);
  ioctl(fd, UI_SET_EVBIT, EV_SYN);
  // Keyboard keys only (BTN_* bits make EventHub take it for a stylus), plus the remote's own codes.
  for (int k = 1; k < 256; k++) ioctl(fd, UI_SET_KEYBIT, k);
  int extra[] = {353, 362, 744, 745, 746, 747, 748};
  for (unsigned i = 0; i < sizeof extra / sizeof *extra; i++) ioctl(fd, UI_SET_KEYBIT, extra[i]);
  struct uinput_user_dev dev;
  memset(&dev, 0, sizeof dev);
  snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "Glass Virtual Remote");
  dev.id.bustype = BUS_BLUETOOTH; dev.id.vendor = 0x0171; dev.id.product = 0x0427; dev.id.version = 1;
  write(fd, &dev, sizeof dev);
  if (ioctl(fd, UI_DEV_CREATE) < 0) { perror("create"); return 1; }
  usleep(350 * 1000);  // InputReader has to open the new device before events count
  for (int i = 2; i < argc; i++) {
    if (argv[i][0] == 'w') { usleep(atoi(argv[i] + 1) * 1000); continue; }
    if (argv[i][0] == 'g') {
      const char *guard = getenv("GLASS_PRESS_GUARD");
      if (guard && *guard && system(guard) != 0) { usleep(150 * 1000); ioctl(fd, UI_DEV_DESTROY); close(fd); return 3; }
      continue;
    }
    int code = atoi(argv[i]);
    char *c = strchr(argv[i], ':');
    int hold = c ? atoi(c + 1) : 0;
    emit(fd, EV_KEY, code, 1); emit(fd, EV_SYN, SYN_REPORT, 0);
    if (hold) usleep(hold * 1000); else usleep(30 * 1000);
    emit(fd, EV_KEY, code, 0); emit(fd, EV_SYN, SYN_REPORT, 0);
    usleep(gap * 1000);
  }
  usleep(150 * 1000);
  ioctl(fd, UI_DEV_DESTROY);
  close(fd);
  return 0;
}
