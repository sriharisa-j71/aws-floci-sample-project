package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"math/rand"
	"sync"
	"sync/atomic"
	"time"

	"github.com/brianvoe/gofakeit/v7"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var emailSeq atomic.Int64
var phoneSeq atomic.Int64

type InvestorRow struct {
	fullName         string
	annualIncomeUSD  float64
	customerSegment  string
	domicileCurrency string
	joinDate         time.Time
	bankAccounts     string
	address          string
}

type DetailRow struct {
	customerID       int
	email            string
	phone            string
	dateOfBirth      time.Time
	gender           string
	employmentStatus string
}

type RiskProfileRow struct {
	customerID int
	profile    string
	score      *int
	startDate  time.Time
	endDate    *time.Time
	isActive   bool
}

type InvestmentRow struct {
	customerID      int
	invType         string
	ticker          *string
	description     string
	quantity        float64
	purchasePriceUSD float64
	purchasePriceDOM float64
	currentValueUSD  float64
	currentValueDOM  float64
	valuationDate   time.Time
	startDate       time.Time
	endDate         *time.Time
	isActive        bool
}

type LiabilityRow struct {
	customerID       int
	liabType         string
	creditor         string
	totalUSD         float64
	totalDOM         float64
	outstandingUSD   float64
	outstandingDOM   float64
	interestRate     float64
	monthlyPaymentUSD float64
	monthlyPaymentDOM float64
	startDate        time.Time
	endDate          *time.Time
	isActive         bool
}

type BatchData struct {
	investors   []InvestorRow
	details     []DetailRow
	profiles    []RiskProfileRow
	investments []InvestmentRow
	liabilities []LiabilityRow
}

type WorkUnit struct {
	startID int
	count   int
}

type WorkerResult struct {
	workerID        int
	startID         int
	count           int
	profileCount    int
	investmentCount int
	liabilityCount  int
	err             error
}

var (
	segments    = []string{"Wealth", "Premium", "Retail", "Corporate"}
	currencies  = []string{"USD", "GBP", "CHF", "EUR", "JPY"}
	genders     = []string{"Male", "Female", "Non-Binary", "Other"}
	emplTypes   = []string{"Employed", "Self-Employed", "Retired", "Student", "Unemployed"}
	invTypes    = []string{"Stocks", "Bonds", "ETF", "Mutual Funds", "Real Estate", "Crypto", "Commodities", "Fixed Deposit"}
	liabTypes   = []string{"Mortgage", "Auto Loan", "Personal Loan", "Student Loan", "Credit Card", "Business Loan", "Other"}
	riskProfiles = []string{"Conservative", "Moderate", "Aggressive"}

	stockDescriptions = []string{"Apple Inc", "Alphabet Inc", "Microsoft Corp", "Amazon.com Inc", "Tesla Inc",
		"JPMorgan Chase", "Visa Inc", "NVIDIA Corp", "Meta Platforms", "Berkshire Hathaway",
		"Coca-Cola Co", "Walt Disney Co", "Netflix Inc", "Boeing Co", "Walmart Inc"}
	bondDescriptions  = []string{"US Treasury 10Y Note", "Corporate IG Bond ETF", "Municipal Bond Fund", "TIPS Fund",
		"EM Bond Fund", "High Yield Corp ETF", "Agency MBS Fund"}
	reDescriptions    = []string{"REIT Index Fund", "Commercial Property Fund", "Residential REIT", "Infrastructure Fund", "Timber REIT"}
	commodityDescriptions = []string{"Gold ETF", "Silver Futures Fund", "Oil Fund", "Agricultural ETF", "Precious Metals Fund", "Copper Futures Fund"}
	cryptoDescriptions = []string{"Bitcoin Trust", "Ethereum Fund", "Solana Trust", "Chainlink Fund", "Cardano Trust", "Polkadot Fund", "Avalanche Fund"}
	fdDescriptions    = []string{"12-Month Fixed Deposit", "24-Month Fixed Deposit", "36-Month Fixed Deposit", "6-Month Fixed Deposit"}

	stockTickers   = []string{"AAPL", "GOOGL", "MSFT", "AMZN", "TSLA", "JPM", "V", "NVDA", "META", "BRK.B", "KO", "DIS", "NFLX", "BA", "WMT"}
	bondTickers    = []string{"GOVT", "LQD", "MUB", "TIP", "EMB", "HYG", "MBB"}
	reTickers      = []string{"VNQ", "ICF", "SCHH", "ROOF", "CTRex"}
	commodityTickers = []string{"GLD", "SLV", "USO", "DBA", "GDX", "CPER"}
	cryptoTickers  = []string{"GBTC", "ETHE", "SOL", "LINK", "ADA", "DOT", "AVAX"}
	fdTickers      = []string{"FD1Y", "FD2Y", "FD3Y", "FD6M"}

	invTypesAndDesc = []struct {
		invType    string
		descs      []string
		tickers    []string
	}{
		{"Stocks",       stockDescriptions,       stockTickers},
		{"Bonds",        bondDescriptions,        bondTickers},
		{"ETF",          bondDescriptions,        bondTickers},
		{"Mutual Funds", reDescriptions,          reTickers},
		{"Real Estate",  reDescriptions,          reTickers},
		{"Crypto",       cryptoDescriptions,      cryptoTickers},
		{"Commodities",  commodityDescriptions,   commodityTickers},
		{"Fixed Deposit", fdDescriptions,         fdTickers},
	}

	currencyRates = map[string]float64{
		"USD": 1.0,
		"GBP": 0.79,
		"CHF": 0.89,
		"EUR": 0.92,
		"JPY": 158.5,
	}
)

func round2(v float64) float64 { return math.Round(v*100) / 100 }

func domAmount(usd float64, cur string) float64 {
	return round2(usd * currencyRates[cur])
}

func fmtAddr(street, city, state, zip, country string) string {
	b, _ := json.Marshal(map[string]string{
		"street": street, "city": city, "state": state, "zip": zip, "country": country,
	})
	return string(b)
}

func fmtBank(acct, routing, bank string) string {
	b, _ := json.Marshal(map[string]string{
		"account_number": acct, "routing_number": routing, "bank_name": bank,
	})
	return string(b)
}

func newDate(y, m, d int) time.Time {
	return time.Date(y, time.Month(m), d, 0, 0, 0, 0, time.UTC)
}

func randDate(rng *rand.Rand, start, end time.Time) time.Time {
	delta := int(end.Sub(start).Seconds())
	if delta <= 0 {
		return start
	}
	return start.Add(time.Duration(rng.Intn(delta)) * time.Second)
}

func genFamily(rng *rand.Rand, startID, size int, sharedAddr, sharedCur string) BatchData {
	now := time.Now()
	familyName := gofakeit.LastName()

	bd := BatchData{
		investors:   make([]InvestorRow, 0, size),
		details:     make([]DetailRow, 0, size),
		profiles:    make([]RiskProfileRow, 0, size*2),
		investments: make([]InvestmentRow, 0, size*4),
		liabilities: make([]LiabilityRow, 0, size*2),
	}

	type roleConf struct {
		first      string
		ageMin     int
		ageMax     int
		incomeMult float64
		employment string
	}

	roles := make([]roleConf, size)
	roles[0] = roleConf{
		first:      gofakeit.FirstName(),
		ageMin:     35, ageMax: 65,
		incomeMult: 1.0,
		employment: "Employed",
	}

	for i := 1; i < size; i++ {
		ageMin, ageMax := 5, 25
		incomeMult := 0.0
		empl := "Student"

		if rng.Float64() < 0.4 && size >= 3 && i == 1 {
			ageMin, ageMax = 30, 60
			incomeMult = 0.5
			empl = "Employed"
		}

		roles[i] = roleConf{
			first:      gofakeit.FirstName(),
			ageMin:     ageMin, ageMax: ageMax,
			incomeMult: incomeMult,
			employment: empl,
		}
	}

	for i := 0; i < size; i++ {
		cid := startID + i
		rl := roles[i]
		fullName := rl.first + " " + familyName
		joinDate := randDate(rng, newDate(2015, 1, 1), now)

		var income float64
		seg := segments[rng.Intn(len(segments))]
		if seg == "Wealth" {
			income = float64(rng.Intn(400000)+200000) * math.Max(rl.incomeMult, 0.3)
		} else if seg == "Premium" {
			income = float64(rng.Intn(130000)+70000) * math.Max(rl.incomeMult, 0.3)
		} else if seg == "Retail" {
			income = float64(rng.Intn(55000)+25000) * math.Max(rl.incomeMult, 0.3)
		} else {
			income = float64(rng.Intn(900000)+100000) * math.Max(rl.incomeMult, 0.3)
		}

		bd.investors = append(bd.investors, InvestorRow{
			fullName:         fullName,
			annualIncomeUSD:  round2(income),
			customerSegment:  seg,
			domicileCurrency: sharedCur,
			joinDate:         joinDate,
			bankAccounts:     fmtBank(fmt.Sprintf("%012d", rng.Intn(999999999999)), fmt.Sprintf("%09d", rng.Intn(999999999)), gofakeit.Company()),
			address:          sharedAddr,
		})

		dob := randDate(rng, newDate(2025-rl.ageMax, 1, 1), newDate(2025-rl.ageMin, 12, 31))
		email := fmt.Sprintf("person%d@email.com", emailSeq.Add(1))
		phone := fmt.Sprintf("+1-555-%07d", phoneSeq.Add(1))

		gender := genders[rng.Intn(len(genders))]
		bd.details = append(bd.details, DetailRow{
			customerID:       cid,
			email:            email,
			phone:            phone,
			dateOfBirth:      dob,
			gender:           gender,
			employmentStatus: rl.employment,
		})

		genRiskProfiles(rng, cid, joinDate, now, &bd)
		genInvestments(rng, cid, seg, sharedCur, joinDate, now, &bd)
		genLiabilities(rng, cid, seg, sharedCur, income, joinDate, now, &bd)
	}

	return bd
}

func genSingle(rng *rand.Rand, cid int) BatchData {
	now := time.Now()
	seg := segments[rng.Intn(len(segments))]
	cur := currencies[rng.Intn(len(currencies))]

	var income float64
	switch seg {
	case "Wealth":
		income = float64(rng.Intn(400000) + 200000)
	case "Premium":
		income = float64(rng.Intn(130000) + 70000)
	case "Retail":
		income = float64(rng.Intn(55000) + 25000)
	case "Corporate":
		income = float64(rng.Intn(900000) + 100000)
	}

	joinDate := randDate(rng, newDate(2015, 1, 1), now)
	fullName := gofakeit.Name()
	addr := fmtAddr(gofakeit.Street(), gofakeit.City(), gofakeit.State(), gofakeit.Zip(), cur)

	bd := BatchData{
		investors: []InvestorRow{
			{
				fullName:         fullName,
				annualIncomeUSD:  float64(income),
				customerSegment:  seg,
				domicileCurrency: cur,
				joinDate:         joinDate,
				bankAccounts:     fmtBank(fmt.Sprintf("%012d", rng.Intn(999999999999)), fmt.Sprintf("%09d", rng.Intn(999999999)), gofakeit.Company()),
				address:          addr,
			},
		},
		details:     make([]DetailRow, 0, 1),
		profiles:    make([]RiskProfileRow, 0, 2),
		investments: make([]InvestmentRow, 0, 4),
		liabilities: make([]LiabilityRow, 0, 3),
	}

	dob := randDate(rng, newDate(1955, 1, 1), newDate(2000, 1, 1))
	email := fmt.Sprintf("person%d@email.com", emailSeq.Add(1))
	phone := fmt.Sprintf("+1-555-%07d", phoneSeq.Add(1))
	gender := genders[rng.Intn(len(genders))]
	empl := emplTypes[rng.Intn(len(emplTypes))]

	bd.details = append(bd.details, DetailRow{
		customerID:       cid,
		email:            email,
		phone:            phone,
		dateOfBirth:      dob,
		gender:           gender,
		employmentStatus: empl,
	})

	genRiskProfiles(rng, cid, joinDate, now, &bd)
	genInvestments(rng, cid, seg, cur, joinDate, now, &bd)
	genLiabilities(rng, cid, seg, cur, income, joinDate, now, &bd)

	return bd
}

func genRiskProfiles(rng *rand.Rand, cid int, joinDate, now time.Time, bd *BatchData) {
	num := rng.Intn(3) + 1
	start := joinDate
	for j := 0; j < num; j++ {
		prof := riskProfiles[rng.Intn(len(riskProfiles))]
		var score int
		switch prof {
		case "Conservative":
			score = rng.Intn(20) + 10
		case "Moderate":
			score = rng.Intn(30) + 35
		case "Aggressive":
			score = rng.Intn(20) + 70
		}
		scoreVal := &score
		isActive := j == num-1
		var end *time.Time
		if !isActive {
			e := start.AddDate(0, rng.Intn(24)+6, 0)
			end = &e
		}
		bd.profiles = append(bd.profiles, RiskProfileRow{
			customerID: cid,
			profile:    prof,
			score:      scoreVal,
			startDate:  start,
			endDate:    end,
			isActive:   isActive,
		})
		if !isActive {
			start = end.AddDate(0, 1, 0)
		}
	}
}

func genInvestments(rng *rand.Rand, cid int, seg, cur string, joinDate, now time.Time, bd *BatchData) {
	num := rng.Intn(4) + 2
	for j := 0; j < num; j++ {
		entry := invTypesAndDesc[rng.Intn(len(invTypesAndDesc))]
		desc := entry.descs[rng.Intn(len(entry.descs))]
		ticker := entry.tickers[rng.Intn(len(entry.tickers))]

		qty := round2(float64(rng.Intn(1000)+10) + float64(rng.Intn(100))/100)
		purchasePrice := round2(float64(rng.Intn(500)+10) + float64(rng.Intn(100))/100)
		priceChange := 0.8 + float64(rng.Intn(50))/100
		currentVal := round2(purchasePrice * priceChange * qty)

		invStart := randDate(rng, joinDate, now)
		isActive := rng.Float64() > 0.25
		var invEnd *time.Time
		if !isActive {
			e := invStart.AddDate(0, rng.Intn(18)+3, 0)
			invEnd = &e
		}

		tickerVal := &ticker
		if entry.invType == "Fixed Deposit" {
			tickerVal = nil
		}

		bd.investments = append(bd.investments, InvestmentRow{
			customerID:       cid,
			invType:          entry.invType,
			ticker:           tickerVal,
			description:      desc,
			quantity:         qty,
			purchasePriceUSD: purchasePrice,
			purchasePriceDOM: domAmount(purchasePrice, cur),
			currentValueUSD:  currentVal,
			currentValueDOM:  domAmount(currentVal, cur),
			valuationDate:    invStart,
			startDate:        invStart,
			endDate:          invEnd,
			isActive:         isActive,
		})
	}
}

func genLiabilities(rng *rand.Rand, cid int, seg, cur string, income float64, joinDate, now time.Time, bd *BatchData) {
	num := rng.Intn(4)
	for j := 0; j < num; j++ {
		lt := liabTypes[rng.Intn(len(liabTypes))]
		var totalUSD, monthlyUSD float64
		switch lt {
		case "Mortgage":
			totalUSD = float64(rng.Intn(700000) + 100000)
			monthlyUSD = round2(totalUSD * 0.0045)
		case "Auto Loan":
			totalUSD = float64(rng.Intn(50000) + 10000)
			monthlyUSD = round2(totalUSD * 0.025)
		case "Personal Loan":
			totalUSD = float64(rng.Intn(40000) + 5000)
			monthlyUSD = round2(totalUSD * 0.032)
		case "Student Loan":
			totalUSD = float64(rng.Intn(80000) + 10000)
			monthlyUSD = round2(totalUSD * 0.012)
		case "Credit Card":
			totalUSD = float64(rng.Intn(15000) + 1000)
			monthlyUSD = round2(totalUSD * 0.05)
		case "Business Loan":
			totalUSD = float64(rng.Intn(500000) + 50000)
			monthlyUSD = round2(totalUSD * 0.008)
		case "Other":
			totalUSD = float64(rng.Intn(20000) + 1000)
			monthlyUSD = round2(totalUSD * 0.03)
		}
		outstanding := round2(totalUSD * (0.3 + float64(rng.Intn(70))/100))
		interest := round2(2.0 + float64(rng.Intn(80))/10)

		libStart := randDate(rng, joinDate, now)
		isActive := rng.Float64() > 0.2
		var libEnd *time.Time
		if !isActive {
			e := libStart.AddDate(0, rng.Intn(36)+6, 0)
			libEnd = &e
		}

		bd.liabilities = append(bd.liabilities, LiabilityRow{
			customerID:       cid,
			liabType:         lt,
			creditor:         gofakeit.Company(),
			totalUSD:         totalUSD,
			totalDOM:         domAmount(totalUSD, cur),
			outstandingUSD:   outstanding,
			outstandingDOM:   domAmount(outstanding, cur),
			interestRate:     interest,
			monthlyPaymentUSD: monthlyUSD,
			monthlyPaymentDOM: domAmount(monthlyUSD, cur),
			startDate:        libStart,
			endDate:          libEnd,
			isActive:         isActive,
		})
	}
}

func insertBatch(ctx context.Context, conn *pgxpool.Conn, bd BatchData) error {
	if len(bd.investors) == 0 {
		return nil
	}

	tx, err := conn.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	invRows := make([][]any, len(bd.investors))
	for i, r := range bd.investors {
		invRows[i] = []any{bd.details[i].customerID, r.fullName, r.annualIncomeUSD, r.customerSegment, r.domicileCurrency, r.joinDate, r.bankAccounts}
	}
	_, err = tx.CopyFrom(ctx, pgx.Identifier{"public", "investors"},
		[]string{"customer_id", "full_name", "annual_income_usd", "customer_segment", "domicile_currency", "join_date", "bank_accounts"},
		pgx.CopyFromRows(invRows),
	)
	if err != nil {
		return fmt.Errorf("copy investors: %w", err)
	}

	detRows := make([][]any, len(bd.details))
	for i, r := range bd.details {
		detRows[i] = []any{r.customerID, r.email, r.phone, r.dateOfBirth, r.gender, r.employmentStatus, bd.investors[i].address}
	}
	_, err = tx.CopyFrom(ctx, pgx.Identifier{"public", "personal_details"},
		[]string{"customer_id", "email", "phone", "date_of_birth", "gender", "employment_status", "address"},
		pgx.CopyFromRows(detRows),
	)
	if err != nil {
		return fmt.Errorf("copy personal_details: %w", err)
	}

	maxID := bd.details[len(bd.details)-1].customerID
	_, err = tx.Exec(ctx, "SELECT setval('public.investors_customer_id_seq', $1)", maxID)
	if err != nil {
		return fmt.Errorf("update seq: %w", err)
	}

	if len(bd.profiles) > 0 {
		rpRows := make([][]any, len(bd.profiles))
		for i, r := range bd.profiles {
			rpRows[i] = []any{r.customerID, r.profile, r.score, r.startDate, r.endDate, r.isActive}
		}
		_, err = tx.CopyFrom(ctx, pgx.Identifier{"public", "risk_profiles"},
			[]string{"customer_id", "risk_profile", "risk_score", "effective_start_date", "effective_end_date", "is_active"},
			pgx.CopyFromRows(rpRows),
		)
		if err != nil {
			return fmt.Errorf("copy risk_profiles: %w", err)
		}
	}

	if len(bd.investments) > 0 {
		ivRows := make([][]any, len(bd.investments))
		for i, r := range bd.investments {
			ivRows[i] = []any{r.customerID, r.invType, r.ticker, r.description, r.quantity,
				r.purchasePriceUSD, r.purchasePriceDOM, r.currentValueUSD, r.currentValueDOM,
				r.valuationDate, r.startDate, r.endDate, r.isActive}
		}
		_, err = tx.CopyFrom(ctx, pgx.Identifier{"public", "investments"},
			[]string{"customer_id", "type", "ticker", "description", "quantity",
				"purchase_price_usd", "purchase_price_dom", "current_value_usd", "current_value_dom",
				"valuation_date", "effective_start_date", "effective_end_date", "is_active"},
			pgx.CopyFromRows(ivRows),
		)
		if err != nil {
			return fmt.Errorf("copy investments: %w", err)
		}
	}

	if len(bd.liabilities) > 0 {
		lbRows := make([][]any, len(bd.liabilities))
		for i, r := range bd.liabilities {
			lbRows[i] = []any{r.customerID, r.liabType, r.creditor,
				r.totalUSD, r.totalDOM, r.outstandingUSD, r.outstandingDOM,
				r.interestRate, r.monthlyPaymentUSD, r.monthlyPaymentDOM,
				r.startDate, r.endDate, r.isActive}
		}
		_, err = tx.CopyFrom(ctx, pgx.Identifier{"public", "liabilities"},
			[]string{"customer_id", "type", "creditor",
				"total_amount_usd", "total_amount_dom",
				"outstanding_amount_usd", "outstanding_amount_dom",
				"interest_rate", "monthly_payment_usd", "monthly_payment_dom",
				"effective_start_date", "effective_end_date", "is_active"},
			pgx.CopyFromRows(lbRows),
		)
		if err != nil {
			return fmt.Errorf("copy liabilities: %w", err)
		}
	}

	return tx.Commit(ctx)
}

func worker(ctx context.Context, id int, units <-chan WorkUnit, results chan<- WorkerResult, dsn string, familyPct float64, maxFamilySize int) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		results <- WorkerResult{workerID: id, err: fmt.Errorf("create pool: %w", err)}
		return
	}
	defer pool.Close()

	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(id)*99991))

	for unit := range units {
		total := 0
		profCnt := 0
		invCnt := 0
		libCnt := 0
		i := 0

		for i < unit.count {
			var bd BatchData
			var batchSize int

			if rng.Float64() < familyPct && i+2 <= unit.count {
				famSize := rng.Intn(maxFamilySize-1) + 2
				if i+famSize > unit.count {
					famSize = unit.count - i
				}
				if famSize < 2 {
					famSize = 2
				}
				cur := currencies[rng.Intn(len(currencies))]
				addr := fmtAddr(gofakeit.Street(), gofakeit.City(), gofakeit.State(), gofakeit.Zip(), cur)
				bd = genFamily(rng, unit.startID+i, famSize, addr, cur)
				batchSize = famSize
			} else {
				bd = genSingle(rng, unit.startID+i)
				batchSize = 1
			}

			conn, err := pool.Acquire(ctx)
			if err != nil {
				results <- WorkerResult{workerID: id, err: fmt.Errorf("acquire conn at %d: %w", unit.startID+i, err)}
				return
			}
			err = insertBatch(ctx, conn, bd)
			conn.Release()
			if err != nil {
				results <- WorkerResult{workerID: id, err: fmt.Errorf("batch at %d: %w", unit.startID+i, err)}
				return
			}

			total += batchSize
			profCnt += len(bd.profiles)
			invCnt += len(bd.investments)
			libCnt += len(bd.liabilities)
			i += batchSize
		}

		results <- WorkerResult{
			workerID:        id,
			startID:         unit.startID,
			count:           total,
			profileCount:    profCnt,
			investmentCount: invCnt,
			liabilityCount:  libCnt,
		}
	}
}

func main() {
	total := flag.Int("count", 1000, "number of investors to generate")
	workers := flag.Int("workers", 4, "number of concurrent worker goroutines")
	familyPct := flag.Float64("family-pct", 0.30, "probability an investor belongs to a family group")
	maxFamilySize := flag.Int("max-family", 5, "maximum family size (min 2)")
	dsn := flag.String("dsn", "postgres://admin:secret123@localhost:5432/postgres?sslmode=disable", "PostgreSQL DSN")
	connStr := flag.String("conn", "", "optional separate DSN for coordinator (override -dsn)")
	flag.Parse()

	if *total <= 0 {
		log.Fatal("--count must be positive")
	}
	if *workers <= 0 {
		log.Fatal("--workers must be positive")
	}
	if *maxFamilySize < 2 {
		log.Fatal("--max-family must be >= 2")
	}
	if *familyPct < 0 || *familyPct > 1 {
		log.Fatal("--family-pct must be between 0 and 1")
	}

	coordDSN := *dsn
	if *connStr != "" {
		coordDSN = *connStr
	}

	log.Printf("Connecting to coordinator DB ...")
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, coordDSN)
	if err != nil {
		log.Fatalf("coordinator create pool: %v", err)
	}

	var maxID int
	err = pool.QueryRow(ctx, "SELECT COALESCE(MAX(customer_id), 0) FROM public.investors").Scan(&maxID)
	if err != nil {
		log.Fatalf("max customer_id: %v", err)
	}
	pool.Close()

	startID := maxID + 1
	perWorker := *total / *workers
	remainder := *total % *workers

	log.Printf("Generating %d investors starting at customer_id=%d with %d workers (family-pct=%.0f%%, max-family=%d)",
		*total, startID, *workers, *familyPct*100, *maxFamilySize)

	workChan := make(chan WorkUnit, *workers)
	resultChan := make(chan WorkerResult, *workers)

	var wg sync.WaitGroup
	for w := 0; w < *workers; w++ {
		wg.Add(1)
		go func(wid int) {
			defer wg.Done()
			worker(ctx, wid, workChan, resultChan, *dsn, *familyPct, *maxFamilySize)
		}(w)
	}

	go func() {
		offset := 0
		for w := 0; w < *workers; w++ {
			cnt := perWorker
			if w < remainder {
				cnt++
			}
			if cnt > 0 {
				workChan <- WorkUnit{startID: startID + offset, count: cnt}
				offset += cnt
			}
		}
		close(workChan)
	}()

	go func() {
		wg.Wait()
		close(resultChan)
	}()

	var totalInv, totalProf, totalInvst, totalLib int
	var lastPct int
	done := make(chan struct{})

	go func() {
		for res := range resultChan {
			if res.err != nil {
				log.Fatalf("Worker %d error: %v", res.workerID, res.err)
			}
			totalInv += res.count
			totalProf += res.profileCount
			totalInvst += res.investmentCount
			totalLib += res.liabilityCount

			pct := int(float64(totalInv) / float64(*total) * 100)
			if pct > lastPct {
				lastPct = pct
				log.Printf("Progress: %d/%d (%d%%) — investors:%d profiles:%d investments:%d liabilities:%d",
					totalInv, *total, pct, totalInv, totalProf, totalInvst, totalLib)
			}
		}
		close(done)
	}()

	<-done

	fmt.Println()
	fmt.Println("=== Generation Complete ===")
	fmt.Printf("Investors:     %7d\n", totalInv)
	fmt.Printf("Risk profiles: %7d\n", totalProf)
	fmt.Printf("Investments:   %7d\n", totalInvst)
	fmt.Printf("Liabilities:   %7d\n", totalLib)
	fmt.Printf("Total rows:    %7d\n", totalInv+totalInv+totalProf+totalInvst+totalLib)
}
