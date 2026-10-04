package libcore

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/unix"
)

// getTunnelName returns the kernel name (e.g. tun0) of an open TUN fd,
// mirroring sing-box's libbox helper.
func getTunnelName(fd int) (string, error) {
	var ifr [40]byte
	_, _, errno := unix.Syscall(
		unix.SYS_IOCTL,
		uintptr(fd),
		uintptr(unix.TUNGETIFF),
		uintptr(unsafe.Pointer(&ifr[0])),
	)
	if errno != 0 {
		return "", fmt.Errorf("failed to get name of TUN device: %w", syscall.Errno(errno))
	}
	name := ifr[:]
	if i := indexByte(name, 0); i >= 0 {
		name = name[:i]
	}
	return string(name), nil
}

func indexByte(b []byte, c byte) int {
	for i, x := range b {
		if x == c {
			return i
		}
	}
	return -1
}
