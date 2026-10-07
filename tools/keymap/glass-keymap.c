// glass-keymap: rewrites an input device's kernel keymap (HID usage -> Linux key code).
// Used by tools/magisk/glass-remote-keys/service.sh: remap "Fire TV Remote" 249 185.
// Fire OS catches the remote's Settings button by its key code (249) before any app sees it, whatever
// Android keycode the key layout gives it; changing the code the kernel emits is the only way around.
//
//   glass-keymap dump  <device-name-substring>
//   glass-keymap remap <device-name-substring> <from-code> <to-code>   (idempotent; exit 1 if no device)
//
// Build (static, runs on any 32/64-bit ARM Android): zig cc -target arm-linux-musleabi -static -Os -o glass-keymap glass-keymap.c
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int open_device(const char *want) {
    for (int i = 0; i < 64; i++) {
        char path[32], name[256] = {0};
        snprintf(path, sizeof path, "/dev/input/event%d", i);
        int fd = open(path, O_RDWR);
        if (fd < 0) continue;
        if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) >= 0 && strstr(name, want)) return fd;
        close(fd);
    }
    return -1;
}

// Calls fn for every keymap entry; stops when the driver runs out of indices.
static int each_entry(int fd, int (*fn)(int fd, struct input_keymap_entry *e, void *arg), void *arg) {
    int changed = 0;
    for (unsigned i = 0; i < 4096; i++) {
        struct input_keymap_entry e = {0};
        e.flags = INPUT_KEYMAP_BY_INDEX;
        e.index = i;
        e.len = sizeof(e.scancode);
        if (ioctl(fd, EVIOCGKEYCODE_V2, &e) < 0) break;
        changed += fn(fd, &e, arg);
    }
    return changed;
}

static unsigned scancode(const struct input_keymap_entry *e) {
    unsigned v = 0;
    memcpy(&v, e->scancode, e->len < sizeof v ? e->len : sizeof v);
    return v;
}

static int print_entry(int fd, struct input_keymap_entry *e, void *arg) {
    (void)fd; (void)arg;
    printf("%u\t0x%08x\t%u\n", e->index, scancode(e), e->keycode);
    return 0;
}

struct remap { unsigned from, to; };

static int remap_entry(int fd, struct input_keymap_entry *e, void *arg) {
    struct remap *r = arg;
    if (e->keycode != r->from) return 0;
    struct input_keymap_entry s = {0};
    s.len = e->len;
    memcpy(s.scancode, e->scancode, e->len);
    s.keycode = r->to;
    if (ioctl(fd, EVIOCSKEYCODE_V2, &s) < 0) { perror("EVIOCSKEYCODE_V2"); return 0; }
    printf("0x%08x: %u -> %u\n", scancode(e), r->from, r->to);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s dump|remap <device> [from to]\n", argv[0]); return 2; }
    int fd = open_device(argv[2]);
    if (fd < 0) { fprintf(stderr, "no input device matching \"%s\"\n", argv[2]); return 1; }
    if (!strcmp(argv[1], "dump")) { each_entry(fd, print_entry, NULL); return 0; }
    if (!strcmp(argv[1], "remap") && argc == 5) {
        struct remap r = { (unsigned)atoi(argv[3]), (unsigned)atoi(argv[4]) };
        each_entry(fd, remap_entry, &r);
        return 0;
    }
    fprintf(stderr, "usage: %s dump|remap <device> [from to]\n", argv[0]);
    return 2;
}
