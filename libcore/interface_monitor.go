package libcore

import (
	"net"
	"net/netip"
	"strings"
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// interfaceMonitor is a real tun.DefaultInterfaceMonitor implementation.
//
// The previous stub always returned a nil DefaultInterface, which made the
// 1.14 NetworkManager log "network: missing default interface" at startup
// and latch the pause manager into NetworkPause (urltest groups and
// wireguard endpoints then never wake up). There is no Java-side callback
// feeding us the Android default network, so the underlying interface is
// discovered from the OS interface list on each query: the first up,
// non-loopback interface with a global IPv4 address that is not one of our
// own TUN interfaces. Actual outbound dialing does not bind to this
// interface (dialer.DoNotSelectInterface stays on and sockets are protected
// through the platform), so this only feeds interface-state bookkeeping.

type interfaceMonitor struct {
	access       sync.Mutex
	callbacks    list.List[tun.DefaultInterfaceUpdateCallback]
	myInterfaces []string
}

func newInterfaceMonitor() *interfaceMonitor {
	return &interfaceMonitor{}
}

func (s *interfaceMonitor) Start() error {
	return nil
}

func (s *interfaceMonitor) Close() error {
	return nil
}

func (s *interfaceMonitor) DefaultInterface() *control.Interface {
	s.access.Lock()
	myInterfaces := append([]string(nil), s.myInterfaces...)
	s.access.Unlock()

	interfaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var fallback *control.Interface
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if strings.HasPrefix(iface.Name, "tun") {
			continue
		}
		skip := false
		for _, name := range myInterfaces {
			if iface.Name == name {
				skip = true
				break
			}
		}
		if skip {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		var prefixes []netip.Prefix
		hasGlobal4 := false
		for _, address := range addresses {
			var ip net.IP
			switch addr := address.(type) {
			case *net.IPNet:
				ip = addr.IP
			case *net.IPAddr:
				ip = addr.IP
			}
			if ip == nil {
				continue
			}
			addr, ok := netipxFromIP(ip)
			if !ok {
				continue
			}
			prefixes = append(prefixes, netip.PrefixFrom(addr, addr.BitLen()))
			if addr.Is4() && addr.IsGlobalUnicast() {
				hasGlobal4 = true
			}
		}
		if !hasGlobal4 {
			continue
		}
		result := &control.Interface{
			Index:        iface.Index,
			MTU:          iface.MTU,
			Name:         iface.Name,
			HardwareAddr: iface.HardwareAddr,
			Flags:        iface.Flags,
			Addresses:    prefixes,
		}
		// Prefer the usual underlying transports over virtual leftovers.
		if strings.HasPrefix(iface.Name, "wlan") ||
			strings.HasPrefix(iface.Name, "rmnet") ||
			strings.HasPrefix(iface.Name, "eth") {
			return result
		}
		if fallback == nil {
			fallback = result
		}
	}
	return fallback
}

func netipxFromIP(ip net.IP) (netip.Addr, bool) {
	if ip4 := ip.To4(); ip4 != nil {
		return netip.AddrFrom4([4]byte(ip4)), true
	}
	if ip16 := ip.To16(); ip16 != nil {
		return netip.AddrFrom16([16]byte(ip16)), true
	}
	return netip.Addr{}, false
}

func (s *interfaceMonitor) OverrideAndroidVPN() bool {
	return false
}

func (s *interfaceMonitor) AndroidVPNEnabled() bool {
	return false
}

func (s *interfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	s.access.Lock()
	defer s.access.Unlock()
	return s.callbacks.PushBack(callback)
}

func (s *interfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	if element == nil {
		return
	}
	s.access.Lock()
	defer s.access.Unlock()
	s.callbacks.Remove(element)
}

func (s *interfaceMonitor) RegisterMyInterface(interfaceName string) {
	if interfaceName == "" {
		return
	}
	s.access.Lock()
	defer s.access.Unlock()
	for _, name := range s.myInterfaces {
		if name == interfaceName {
			return
		}
	}
	s.myInterfaces = append(s.myInterfaces, interfaceName)
}

func (s *interfaceMonitor) MyInterfaces() []string {
	s.access.Lock()
	defer s.access.Unlock()
	return append([]string(nil), s.myInterfaces...)
}
