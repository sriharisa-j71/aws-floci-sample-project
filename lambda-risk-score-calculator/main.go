package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"os"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/jackc/pgx/v5/pgxpool"
)

var dbPool *pgxpool.Pool

type InvestorData struct {
	CustomerID       int
	AnnualIncomeUSD  float64
	DomicileCurrency string
	RiskProfile      string
	RiskScore        *int
}

type InvestmentSummary struct {
	TotalPurchaseUSD float64
	TotalCurrentUSD  float64
	TypeNames        []string
}

type LiabilitySummary struct {
	TotalOutstandingUSD float64
	TypeNames           []string
}

type RiskScoreResult struct {
	CustomerID           int
	RiskProfile          string
	RiskScore            *int
	InvestmentCount      int
	InvestmentDiversity  int
	TotalInvestmentUSD   float64
	TotalLiabilityUSD    float64
	DebtToIncomeRatio    float64
	CompositeScore       float64
	RiskCategory         string
}

func init() {
	dsn := os.Getenv("DB_DSN")
	if dsn == "" {
		dsn = "postgres://admin:secret123@postgres:5432/postgres?sslmode=disable"
	}

	ctx := context.Background()
	var err error
	dbPool, err = pgxpool.New(ctx, dsn)
	if err != nil {
		log.Fatalf("db pool: %v", err)
	}
	log.Println("Connected to PostgreSQL")
}

func handleS3Event(ctx context.Context, event events.S3Event) error {
	log.Printf("Received S3 event with %d records", len(event.Records))
	for _, r := range event.Records {
		log.Printf("Triggered by s3://%s/%s", r.S3.Bucket.Name, r.S3.Object.Key)
	}

	rates, err := loadCurrencyRates(ctx)
	if err != nil {
		return fmt.Errorf("load currency rates: %w", err)
	}
	log.Printf("Loaded %d currency rates", len(rates))

	investors, err := loadAllInvestors(ctx)
	if err != nil {
		return fmt.Errorf("load investors: %w", err)
	}
	log.Printf("Loaded %d investors", len(investors))

	var results []RiskScoreResult
	for _, inv := range investors {
		result, err := calculateInvestorRisk(ctx, inv, rates)
		if err != nil {
			log.Printf("Error scoring investor %d: %v", inv.CustomerID, err)
			continue
		}
		results = append(results, result)
	}

	if err := upsertRiskScores(ctx, results); err != nil {
		return fmt.Errorf("upsert risk scores: %w", err)
	}

	log.Printf("Successfully calculated and stored %d risk scores", len(results))
	return nil
}

func loadCurrencyRates(ctx context.Context) (map[string]float64, error) {
	rows, err := dbPool.Query(ctx,
		`SELECT target_currency, rate FROM currency_rates WHERE base_currency = 'USD'`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	rates := make(map[string]float64)
	for rows.Next() {
		var target string
		var rate float64
		if err := rows.Scan(&target, &rate); err != nil {
			return nil, err
		}
		rates[target] = rate
	}
	return rates, nil
}

func loadAllInvestors(ctx context.Context) ([]InvestorData, error) {
	rows, err := dbPool.Query(ctx, `
		SELECT i.customer_id, i.annual_income_usd, i.domicile_currency,
		       COALESCE(rp.risk_profile, 'Moderate'),
		       rp.risk_score
		FROM investors i
		LEFT JOIN risk_profiles rp ON rp.customer_id = i.customer_id AND rp.is_active
		ORDER BY i.customer_id
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var investors []InvestorData
	for rows.Next() {
		var inv InvestorData
		if err := rows.Scan(&inv.CustomerID, &inv.AnnualIncomeUSD,
			&inv.DomicileCurrency, &inv.RiskProfile, &inv.RiskScore); err != nil {
			return nil, err
		}
		investors = append(investors, inv)
	}
	return investors, nil
}

func loadInvestmentSummary(ctx context.Context, customerID int) (InvestmentSummary, error) {
	var summary InvestmentSummary
	rows, err := dbPool.Query(ctx, `
		SELECT i.type, i.purchase_price_usd, i.current_value_usd
		FROM investments i
		WHERE i.customer_id = $1 AND i.is_active
	`, customerID)
	if err != nil {
		return summary, err
	}
	defer rows.Close()

	for rows.Next() {
		var typeName string
		var purchaseTotal, currentValue float64
		if err := rows.Scan(&typeName, &purchaseTotal, &currentValue); err != nil {
			return summary, err
		}
		summary.TotalPurchaseUSD += purchaseTotal
		summary.TotalCurrentUSD += currentValue
		summary.TypeNames = append(summary.TypeNames, typeName)
	}
	return summary, nil
}

func loadLiabilitySummary(ctx context.Context, customerID int) (LiabilitySummary, error) {
	var summary LiabilitySummary
	rows, err := dbPool.Query(ctx, `
		SELECT l.type, l.outstanding_amount_usd
		FROM liabilities l
		WHERE l.customer_id = $1 AND l.is_active
	`, customerID)
	if err != nil {
		return summary, err
	}
	defer rows.Close()

	for rows.Next() {
		var typeName string
		var outstanding float64
		if err := rows.Scan(&typeName, &outstanding); err != nil {
			return summary, err
		}
		summary.TotalOutstandingUSD += outstanding
		summary.TypeNames = append(summary.TypeNames, typeName)
	}
	return summary, nil
}

func calculateProfileScore(profile string, score *int) float64 {
	if score != nil {
		return float64(*score)
	}
	switch profile {
	case "Conservative":
		return 25
	case "Moderate":
		return 50
	case "Aggressive":
		return 75
	default:
		return 50
	}
}

func calculateInvestmentRisk(inv InvestmentSummary) float64 {
	if inv.TotalCurrentUSD <= 0 {
		return 50
	}

	typeSet := make(map[string]bool)
	for _, t := range inv.TypeNames {
		typeSet[t] = true
	}
	typeCount := len(typeSet)
	if typeCount <= 0 {
		typeCount = 1
	}

	diversity := float64(typeCount) / 8.0
	if diversity > 1 {
		diversity = 1
	}
	diversityScore := (1 - diversity) * 100

	perfRatio := inv.TotalPurchaseUSD / inv.TotalCurrentUSD
	var perfScore float64
	if perfRatio < 1 {
		perfScore = 50 - (1-perfRatio)*50
		if perfScore < 0 {
			perfScore = 0
		}
	} else {
		perfScore = 50 + (perfRatio-1)*50
		if perfScore > 100 {
			perfScore = 100
		}
	}

	return diversityScore*0.5 + perfScore*0.5
}

func calculateLiabilityRisk(inv InvestorData, liabilities LiabilitySummary) float64 {
	if liabilities.TotalOutstandingUSD <= 0 || inv.AnnualIncomeUSD <= 0 {
		return 0
	}

	dti := liabilities.TotalOutstandingUSD / inv.AnnualIncomeUSD
	dtiScore := math.Min(100, dti*20)

	liabCount := len(liabilities.TypeNames)
	if liabCount <= 0 {
		liabCount = 1
	}
	countScore := math.Min(100, float64(liabCount)*25)

	return dtiScore*0.6 + countScore*0.4
}

func determineCategory(score float64) string {
	switch {
	case score <= 20:
		return "Very Low"
	case score <= 40:
		return "Low"
	case score <= 55:
		return "Moderate"
	case score <= 70:
		return "High"
	default:
		return "Critical"
	}
}

func calculateInvestorRisk(ctx context.Context, inv InvestorData, rates map[string]float64) (RiskScoreResult, error) {
	var result RiskScoreResult
	result.CustomerID = inv.CustomerID
	result.RiskProfile = inv.RiskProfile
	result.RiskScore = inv.RiskScore

	investments, err := loadInvestmentSummary(ctx, inv.CustomerID)
	if err != nil {
		return result, fmt.Errorf("load investments: %w", err)
	}

	liabilities, err := loadLiabilitySummary(ctx, inv.CustomerID)
	if err != nil {
		return result, fmt.Errorf("load liabilities: %w", err)
	}

	result.InvestmentCount = len(investments.TypeNames)
	result.InvestmentDiversity = len(uniqueStrings(investments.TypeNames))
	result.TotalInvestmentUSD = investments.TotalCurrentUSD
	result.TotalLiabilityUSD = liabilities.TotalOutstandingUSD

	if inv.AnnualIncomeUSD > 0 {
		result.DebtToIncomeRatio = liabilities.TotalOutstandingUSD / inv.AnnualIncomeUSD
	}

	profileScore := calculateProfileScore(inv.RiskProfile, inv.RiskScore)
	investmentScore := calculateInvestmentRisk(investments)
	liabilityScore := calculateLiabilityRisk(inv, liabilities)

	composite := profileScore*0.30 + investmentScore*0.25 + liabilityScore*0.45
	result.CompositeScore = math.Round(composite*100) / 100
	result.RiskCategory = determineCategory(result.CompositeScore)

	return result, nil
}

func uniqueStrings(s []string) []string {
	seen := make(map[string]bool)
	var result []string
	for _, v := range s {
		if !seen[v] {
			seen[v] = true
			result = append(result, v)
		}
	}
	return result
}

func upsertRiskScores(ctx context.Context, results []RiskScoreResult) error {
	if len(results) == 0 {
		return nil
	}

	tx, err := dbPool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	for _, r := range results {
		_, err := tx.Exec(ctx, `
			INSERT INTO daily_risk_scores
				(customer_id, calculation_date, risk_profile, risk_score,
				 investment_count, investment_diversity,
				 total_investment_usd, total_liability_usd,
				 debt_to_income_ratio, composite_score, risk_category)
			VALUES ($1, CURRENT_DATE, $2, $3, $4, $5, $6, $7, $8, $9, $10)
			ON CONFLICT (customer_id, calculation_date)
			DO UPDATE SET
				risk_profile        = EXCLUDED.risk_profile,
				risk_score          = EXCLUDED.risk_score,
				investment_count    = EXCLUDED.investment_count,
				investment_diversity = EXCLUDED.investment_diversity,
				total_investment_usd = EXCLUDED.total_investment_usd,
				total_liability_usd  = EXCLUDED.total_liability_usd,
				debt_to_income_ratio = EXCLUDED.debt_to_income_ratio,
				composite_score     = EXCLUDED.composite_score,
				risk_category       = EXCLUDED.risk_category,
				created_at          = CURRENT_TIMESTAMP
		`, r.CustomerID, r.RiskProfile, r.RiskScore,
			r.InvestmentCount, r.InvestmentDiversity,
			r.TotalInvestmentUSD, r.TotalLiabilityUSD,
			r.DebtToIncomeRatio, r.CompositeScore, r.RiskCategory)
		if err != nil {
			return fmt.Errorf("upsert customer %d: %w", r.CustomerID, err)
		}
	}

	return tx.Commit(ctx)
}

func main() {
	lambda.Start(handleS3Event)
}
