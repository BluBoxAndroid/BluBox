//go:build !with_dhcp

package libcore

import (
	"github.com/sagernet/sing-box/dns"
)

func registerDHCPTransport(registry *dns.TransportRegistry) {
}
