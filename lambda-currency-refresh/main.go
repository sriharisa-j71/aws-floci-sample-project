package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var valuesPerStmt int
var stmtsPerBatch int

type CurrencyRate struct {
	Base     string
	Target   string
	Rate     float64
	Date     time.Time
}

var dbPool *pgxpool.Pool
var s3Client *s3.Client

func init() {
	valuesPerStmt = envInt("VALUES_PER_STMT", 25)
	stmtsPerBatch = envInt("STMTS_PER_BATCH", 10)

	log.Printf("batch config: values_per_stmt=%d stmts_per_batch=%d", valuesPerStmt, stmtsPerBatch)

	dsn := os.Getenv("DB_DSN")
	if dsn == "" {
		dsn = "postgres://admin:secret123@postgres:5432/postgres?sslmode=disable"
	}

	endpoint := os.Getenv("AWS_ENDPOINT_URL")

	ctx := context.Background()

	var err error
	dbPool, err = pgxpool.New(ctx, dsn)
	if err != nil {
		log.Fatalf("db pool: %v", err)
	}

	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		log.Fatalf("aws config: %v", err)
	}

	if endpoint != "" {
		s3Client = s3.NewFromConfig(cfg, func(o *s3.Options) {
			o.BaseEndpoint = &endpoint
			o.UsePathStyle = true
		})
	} else {
		s3Client = s3.NewFromConfig(cfg)
	}
}

func envInt(key string, def int) int {
	s := os.Getenv(key)
	if s == "" {
		return def
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		log.Printf("invalid %s=%q, using default %d", key, s, def)
		return def
	}
	return v
}

func handleS3Event(ctx context.Context, event events.S3Event) error {
	for _, record := range event.Records {
		bucket := record.S3.Bucket.Name
		key := record.S3.Object.Key

		log.Printf("Processing s3://%s/%s", bucket, key)

		result, err := s3Client.GetObject(ctx, &s3.GetObjectInput{
			Bucket: &bucket,
			Key:    &key,
		})
		if err != nil {
			return fmt.Errorf("s3 get object: %w", err)
		}
		defer result.Body.Close()

		data, err := io.ReadAll(result.Body)
		if err != nil {
			return fmt.Errorf("read body: %w", err)
		}

		rates, err := parseRates(string(data))
		if err != nil {
			return fmt.Errorf("parse rates: %w", err)
		}

		if err := upsertRates(ctx, rates); err != nil {
			return fmt.Errorf("upsert rates: %w", err)
		}

		log.Printf("Upserted %d currency rates from s3://%s/%s", len(rates), bucket, key)
	}
	return nil
}

func parseRates(content string) ([]CurrencyRate, error) {
	lines := strings.Split(strings.TrimSpace(content), "\n")
	if len(lines) < 2 {
		return nil, fmt.Errorf("empty or header-only file")
	}

	header := strings.TrimSpace(lines[0])
	expectedHeader := "base_currency|target_currency|rate|effective_date"
	if header != expectedHeader {
		return nil, fmt.Errorf("unexpected header: got %q, want %q", header, expectedHeader)
	}

	var rates []CurrencyRate
	for i, line := range lines[1:] {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Split(line, "|")
		if len(parts) != 4 {
			return nil, fmt.Errorf("line %d: expected 4 pipe-delimited fields, got %d", i+2, len(parts))
		}

		rate, err := strconv.ParseFloat(strings.TrimSpace(parts[2]), 64)
		if err != nil {
			return nil, fmt.Errorf("line %d: invalid rate %q: %w", i+2, parts[2], err)
		}

		dateStr := strings.TrimSpace(parts[3])
		date, err := time.Parse("2006-01-02", dateStr)
		if err != nil {
			return nil, fmt.Errorf("line %d: invalid date %q: %w", i+2, dateStr, err)
		}

		rates = append(rates, CurrencyRate{
			Base:   strings.TrimSpace(parts[0]),
			Target: strings.TrimSpace(parts[1]),
			Rate:   rate,
			Date:   date,
		})
	}

	return rates, nil
}

func flushBatch(ctx context.Context, batch *pgx.Batch) error {
	br := dbPool.SendBatch(ctx, batch)
	defer br.Close()

	for i := 0; i < batch.Len(); i++ {
		_, err := br.Exec()
		if err != nil {
			return fmt.Errorf("batch stmt %d: %w", i, err)
		}
	}
	return nil
}

func upsertRates(ctx context.Context, rates []CurrencyRate) error {
	if len(rates) == 0 {
		return nil
	}

	if _, err := dbPool.Exec(ctx, "TRUNCATE currency_rates"); err != nil {
		return fmt.Errorf("truncate: %w", err)
	}

	batch := &pgx.Batch{}
	stmtCount := 0

	for start := 0; start < len(rates); start += valuesPerStmt {
		end := start + valuesPerStmt
		if end > len(rates) {
			end = len(rates)
		}
		chunk := rates[start:end]

		sb := strings.Builder{}
		sb.WriteString(`INSERT INTO currency_rates (base_currency, target_currency, rate, effective_date) VALUES `)

		args := make([]any, 0, len(chunk)*4)
		paramIdx := 1
		for i, r := range chunk {
			if i > 0 {
				sb.WriteString(", ")
			}
			fmt.Fprintf(&sb, "($%d, $%d, $%d, $%d)", paramIdx, paramIdx+1, paramIdx+2, paramIdx+3)
			paramIdx += 4
			args = append(args, r.Base, r.Target, r.Rate, r.Date)
		}

		batch.Queue(sb.String(), args...)
		stmtCount++

		if stmtCount >= stmtsPerBatch {
			if err := flushBatch(ctx, batch); err != nil {
				return err
			}
			batch = &pgx.Batch{}
			stmtCount = 0
		}
	}

	if stmtCount > 0 {
		return flushBatch(ctx, batch)
	}

	return nil
}

func main() {
	lambda.Start(handleS3Event)
}
