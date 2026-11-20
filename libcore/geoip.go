package libcore

import (
	"fmt"
	"net"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	g.geoipReader = geoipReader
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	var (
		ipNets          []*net.IPNet
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		if strings.EqualFold(nextCountryCode, countryCode) {
			ipNets = append(ipNets, ipNet)
		}
	}
	if err = networks.Err(); err != nil {
		return nil, fmt.Errorf("failed to iterate geoip networks: %w", err)
	}

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func geoIPHeadlessRules(name string) ([]option.HeadlessRule, error) {
	g := new(geoip)
	if err := g.Open(filepath.Join(externalAssetsPath, "geoip.db")); err != nil {
		return nil, err
	}
	defer g.geoipReader.Close()
	return g.Rules(name)
}
