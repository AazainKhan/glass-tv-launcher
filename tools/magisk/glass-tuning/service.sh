#!/system/bin/sh
# Glass memory tuning (scripts/tuning). Measured on the Fire TV Stick 4K: apps were cold-started on
# return because Fire OS caps cached apps at 4, lmkd kills the *heaviest* app first (always the video
# app you were just in), and the 800 MB zram fills up. Removing the module restores all three.


resetprop ro.lmk.kill_heaviest_task false
resetprop ro.lmk.psi_partial_stall_ms 300
resetprop ro.lmk.thrashing_limit 200
resetprop ro.lmk.swap_free_low_percentage 3

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done

stop lmkd; start lmkd
settings put global activity_manager_constants max_cached_processes=12
