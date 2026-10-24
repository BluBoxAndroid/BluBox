package libcore

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// CompileGeoRuleSet compiles a geoip:/geosite: category reference into a
// stock sing-box binary rule-set file (.srs) built from the app's
// geoip.db/geosite.db and returns the file path, for use as the path of a
// local rule-set in the generated config.
//
// The stock kernel treats a local rule-set path as a plain file path, so
// the former geoip:/geosite: prefix interception inside the kernel
// (route/rule/rule_set_local.go) is replaced by this app-layer compile
// step. Compiled files are cached next to the databases and rebuilt when
// a database is updated.
func CompileGeoRuleSet(name string) (string, error) {
	var (
		rules  []option.HeadlessRule
		dbPath string
		err    error
	)
	switch {
	case strings.HasPrefix(name, "geoip:"):
		dbPath = filepath.Join(externalAssetsPath, "geoip.db")
		rules, err = geoIPHeadlessRules(strings.TrimPrefix(name, "geoip:"))
	case strings.HasPrefix(name, "geosite:"):
		dbPath = filepath.Join(externalAssetsPath, "geosite.db")
		rules, err = geoSiteHeadlessRules(strings.TrimPrefix(name, "geosite:"))
	default:
		return "", fmt.Errorf("unsupported rule-set reference: %s", name)
	}
	if err != nil {
		return "", err
	}

	outDir := filepath.Join(externalAssetsPath, "rulesets")
	if err = os.MkdirAll(outDir, 0o755); err != nil {
		return "", err
	}
	outPath := filepath.Join(outDir, ruleSetFileName(name)+".srs")
	if ruleSetOutputFresh(outPath, dbPath) {
		return outPath, nil
	}

	tmpPath := outPath + ".tmp"
	file, err := os.Create(tmpPath)
	if err != nil {
		return "", err
	}
	writeErr := srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersion2)
	closeErr := file.Close()
	if writeErr != nil {
		os.Remove(tmpPath)
		return "", writeErr
	}
	if closeErr != nil {
		os.Remove(tmpPath)
		return "", closeErr
	}
	if err = os.Rename(tmpPath, outPath); err != nil {
		return "", err
	}
	return outPath, nil
}

func ruleSetOutputFresh(outPath string, dbPath string) bool {
	outInfo, err := os.Stat(outPath)
	if err != nil {
		return false
	}
	dbInfo, err := os.Stat(dbPath)
	if err != nil {
		return false
	}
	return !outInfo.ModTime().Before(dbInfo.ModTime())
}

func ruleSetFileName(name string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '_', r == '-':
			return r
		default:
			return '_'
		}
	}, name)
}
