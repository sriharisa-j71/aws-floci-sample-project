package main

import (
	"fmt"
	"os"
	"sync"

	"github.com/BurntSushi/toml"
)

type QueryDef struct {
	Name         string            `toml:"name"`
	SQL          string            `toml:"sql"`
	Params       []string          `toml:"params"`
	DynamicWhere bool              `toml:"dynamic_where"`
	Filters      []string          `toml:"filters"`
	FilterPrefix string            `toml:"filter_prefix"`
	FilterMap    map[string]string `toml:"filter_map"`
	Limit        bool              `toml:"limit"`
	S3Bucket     string            `toml:"s3_bucket"`
	S3Prefix     string            `toml:"s3_prefix"`
}

type QueryConfig struct {
	Queries []QueryDef `toml:"queries"`
}

var (
	codeRegistry map[string]QueryDef
	registryOnce sync.Once
)

func initCodeRegistry() {
	registryOnce.Do(func() {
		defBucket := envOr("S3_BUCKET", "query-results")
		defPrefix := envOr("S3_PREFIX", "query-results")

		codeRegistry = map[string]QueryDef{
			"investors_by_segment": {
				Name:         "investors_by_segment",
				SQL:          "SELECT * FROM investors WHERE customer_segment = $1",
				Params:       []string{"segment"},
				DynamicWhere: false,
				Limit:        true,
				S3Bucket:     defBucket,
				S3Prefix:     defPrefix,
			},
			"risk_report": {
				Name:         "risk_report",
				SQL:          "SELECT i.customer_id, i.full_name, i.customer_segment, drs.composite_score, drs.risk_category, drs.calculation_date FROM investors i JOIN daily_risk_scores drs ON i.customer_id = drs.customer_id WHERE 1=1",
				DynamicWhere: true,
				Filters:      []string{"customer_id", "risk_category", "customer_segment"},
				FilterMap:    map[string]string{"customer_id": "i.customer_id", "customer_segment": "i.customer_segment", "risk_category": "drs.risk_category"},
				Limit:        true,
				S3Bucket:     defBucket,
				S3Prefix:     defPrefix,
			},
			"all_investors": {
				Name:         "all_investors",
				SQL:          "SELECT * FROM investors WHERE 1=1",
				DynamicWhere: true,
				Filters:      []string{"customer_id", "customer_segment", "domicile_currency"},
				Limit:        true,
				S3Bucket:     defBucket,
				S3Prefix:     defPrefix,
			},
			"investments_full": {
				Name:         "investments_full",
				SQL:          "SELECT inv.customer_id, inv.type, inv.ticker, inv.description, inv.quantity, inv.current_value_usd, inv.effective_start_date FROM investments inv WHERE 1=1",
				DynamicWhere: true,
				Filters:      []string{"customer_id", "type", "ticker"},
				Limit:        true,
				S3Bucket:     defBucket,
				S3Prefix:     defPrefix,
			},
			"liabilities_full": {
				Name:         "liabilities_full",
				SQL:          "SELECT li.customer_id, li.type, li.creditor, li.outstanding_amount_usd, li.interest_rate, li.monthly_payment_usd, li.effective_start_date FROM liabilities li WHERE 1=1",
				DynamicWhere: true,
				Filters:      []string{"customer_id", "type", "creditor"},
				Limit:        true,
				S3Bucket:     defBucket,
				S3Prefix:     defPrefix,
			},
		}
	})
}

func loadQueriesFromTOML(path string) (map[string]QueryDef, error) {
	var config QueryConfig
	if _, err := toml.DecodeFile(path, &config); err != nil {
		return nil, fmt.Errorf("toml decode %s: %w", path, err)
	}
	m := make(map[string]QueryDef, len(config.Queries))
	for _, q := range config.Queries {
		m[q.Name] = q
	}
	return m, nil
}

func resolveQuery(name, source string) (QueryDef, error) {
	switch source {
	case "code":
		initCodeRegistry()
		if q, ok := codeRegistry[name]; ok {
			return q, nil
		}
		return QueryDef{}, fmt.Errorf("query %q not found in code registry", name)

	case "toml":
		path := envOr("QUERIES_TOML_PATH", "queries.toml")
		queries, err := loadQueriesFromTOML(path)
		if err != nil {
			return QueryDef{}, err
		}
		if q, ok := queries[name]; ok {
			return q, nil
		}
		return QueryDef{}, fmt.Errorf("query %q not found in toml %s", name, path)

	case "auto", "":
		initCodeRegistry()
		if q, ok := codeRegistry[name]; ok {
			return q, nil
		}
		tomlPath := envOr("QUERIES_TOML_PATH", "queries.toml")
		if _, err := os.Stat(tomlPath); err == nil {
			if queries, err := loadQueriesFromTOML(tomlPath); err == nil {
				if q, ok := queries[name]; ok {
					return q, nil
				}
			}
		}
		return QueryDef{}, fmt.Errorf("query %q not found in any source", name)

	default:
		return QueryDef{}, fmt.Errorf("unknown query source: %q (use \"code\" or \"toml\")", source)
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
