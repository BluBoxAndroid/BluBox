//go:build with_dhcp

package libcore

import (
	"github.com/sagernet/sing-box/dns"
	dhcpTransport "github.com/sagernet/sing-box/dns/transport/dhcp"
)

func registerDHCPTransport(registry *dns.TransportRegistry) {
	dhcpTransport.RegisterTransport(registry)
}
