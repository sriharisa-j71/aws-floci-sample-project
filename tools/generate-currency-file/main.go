package main

import (
	"flag"
	"fmt"
	"math"
	"math/rand"
	"os"
	"time"

	"github.com/brianvoe/gofakeit/v7"
)

type RateEntry struct {
	Base   string
	Target string
	Rate   float64
	Date   string
}

var currencyNames = map[string]string{
	"USD": "United States Dollar",
	"GBP": "British Pound Sterling",
	"CHF": "Swiss Franc",
	"EUR": "Euro",
	"JPY": "Japanese Yen",
	"CAD": "Canadian Dollar",
	"AUD": "Australian Dollar",
	"NZD": "New Zealand Dollar",
	"CNY": "Chinese Yuan",
	"INR": "Indian Rupee",
	"BRL": "Brazilian Real",
	"MXN": "Mexican Peso",
	"SEK": "Swedish Krona",
	"NOK": "Norwegian Krone",
	"KRW": "South Korean Won",
	"SGD": "Singapore Dollar",
}

var baseRates = map[string]float64{
	"USD": 1.0,
	"GBP": 0.79,
	"CHF": 0.89,
	"EUR": 0.92,
	"JPY": 158.5,
	"CAD": 1.36,
	"AUD": 1.52,
	"NZD": 1.64,
	"CNY": 7.25,
	"INR": 83.5,
	"BRL": 5.05,
	"MXN": 17.2,
	"SEK": 10.5,
	"NOK": 10.8,
	"KRW": 1320.0,
	"SGD": 1.34,
}

func round4(v float64) float64 {
	return math.Round(v*10000) / 10000
}

func main() {
	outPath := flag.String("out", "currency_rates.csv", "output file path")
	dateStr := flag.String("date", time.Now().UTC().Format("2006-01-02"), "effective date (YYYY-MM-DD)")
	pairsRaw := flag.String("pairs", "USD/GBP,USD/CHF,USD/EUR,USD/JPY,USD/CAD,USD/AUD,USD/NZD,USD/CNY,USD/INR,USD/BRL,USD/MXN,USD/SEK,USD/NOK,USD/KRW,USD/SGD", "comma-separated base/target pairs")
	variance := flag.Float64("variance", 0.02, "max random variance fraction applied to each rate")
	flag.Parse()

	gofakeit.Seed(time.Now().UnixNano())

	var pairs []struct{ Base, Target string }
	for _, p := range splitAndTrim(*pairsRaw, ",") {
		if len(p) != 7 || p[3] != '/' {
			fmt.Fprintf(os.Stderr, "invalid pair format: %q (expected BASE/TTT)", p)
			os.Exit(1)
		}
		pairs = append(pairs, struct{ Base, Target string }{Base: p[:3], Target: p[4:]})
	}

	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	var entries []RateEntry
	for _, p := range pairs {
		baseRate, baseOK := baseRates[p.Base]
		targetRate, targetOK := baseRates[p.Target]
		if !baseOK {
			fmt.Fprintf(os.Stderr, "unknown base currency: %s\n", p.Base)
			os.Exit(1)
		}
		if !targetOK {
			fmt.Fprintf(os.Stderr, "unknown target currency: %s\n", p.Target)
			os.Exit(1)
		}
		rate := targetRate / baseRate

		jitter := 1.0 + (rng.Float64()*2-1)**variance
		rate = round4(rate * jitter)
		if rate <= 0 {
			rate = 0.0001
		}

		entries = append(entries, RateEntry{
			Base:   p.Base,
			Target: p.Target,
			Rate:   rate,
			Date:   *dateStr,
		})
	}

	f, err := os.Create(*outPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "create file: %v\n", err)
		os.Exit(1)
	}
	defer f.Close()

	fmt.Fprintln(f, "base_currency|target_currency|rate|effective_date")
	for _, e := range entries {
		fmt.Fprintf(f, "%s|%s|%.6f|%s\n", e.Base, e.Target, e.Rate, e.Date)
	}

	fmt.Printf("Wrote %d currency rate entries to %s\n", len(entries), *outPath)
	fmt.Printf("Date: %s\n", *dateStr)
	for _, e := range entries {
		name := currencyNames[e.Target]
		if name == "" {
			name = e.Target
		}
		fmt.Printf("  %s → %s (%s): %.6f\n", e.Base, e.Target, name, e.Rate)
	}
}

func splitAndTrim(s, sep string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(s)-len(sep); i++ {
		if s[i:i+len(sep)] == sep {
			if i > start {
				out = append(out, s[start:i])
			}
			start = i + len(sep)
			i = start - 1
		}
	}
	if start < len(s) {
		out = append(out, s[start:])
	}
	return out
}
