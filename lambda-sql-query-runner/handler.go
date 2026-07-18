package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"github.com/aws/aws-lambda-go/events"
)

type WhereClause struct {
	Conjunction string `json:"conjunction"`
	Condition   string `json:"condition"`
}

type QueryBody struct {
	Params    []any          `json:"params"`
	Where     map[string]any `json:"where"`
	WhereList []WhereClause  `json:"where_list"`
	Limit     int            `json:"limit"`
	Offset    int            `json:"offset"`
}

type QueryResponse struct {
	Status      string `json:"status"`
	Query       string `json:"query"`
	RowCount    int    `json:"row_count"`
	S3Key       string `json:"s3_key,omitempty"`
	S3Bucket    string `json:"s3_bucket,omitempty"`
	ExecutionMs int64  `json:"execution_ms"`
	SQL         string `json:"sql,omitempty"`
	Error       string `json:"error,omitempty"`
}

func handleRequest(ctx context.Context, event events.APIGatewayV2HTTPRequest) (events.APIGatewayV2HTTPResponse, error) {
	start := time.Now()

	queryName := header(event, "x-query-name")
	if queryName == "" {
		return respond(400, QueryResponse{Status: "error", Error: "missing X-Query-Name header"})
	}

	source := header(event, "x-query-source") // "code" or "toml" (default: auto)

	qdef, err := resolveQuery(queryName, source)
	if err != nil {
		return respond(404, QueryResponse{Status: "error", Query: queryName, Error: err.Error()})
	}

	var body QueryBody
	if event.Body != "" {
		if err := json.Unmarshal([]byte(event.Body), &body); err != nil {
			return respond(400, QueryResponse{Status: "error", Query: queryName, Error: fmt.Sprintf("invalid json body: %v", err)})
		}
	}

	sql, args := buildQuery(qdef, body)
	log.Printf("query=%s sql=%s args=%v", queryName, sql, args)

	result, err := executeQuery(ctx, sql, args)
	if err != nil {
		return respond(500, QueryResponse{Status: "error", Query: queryName, Error: err.Error(), SQL: sql})
	}

	output := formatOutput(result)

	bucket := qdef.S3Bucket
	if bucket == "" {
		bucket = envOr("S3_BUCKET", "query-results")
	}
	prefix := qdef.S3Prefix
	if prefix == "" {
		prefix = envOr("S3_PREFIX", "query-results")
	}

	s3Key := buildS3Key(prefix, queryName)
	if err := uploadToS3(ctx, bucket, s3Key, output); err != nil {
		return respond(500, QueryResponse{Status: "error", Query: queryName, Error: err.Error(), SQL: sql})
	}

	elapsed := time.Since(start).Milliseconds()
	log.Printf("query=%s rows=%d s3=%s/%s elapsed=%dms", queryName, result.Count, bucket, s3Key, elapsed)

	return respond(200, QueryResponse{
		Status:      "success",
		Query:       queryName,
		RowCount:    result.Count,
		S3Key:       s3Key,
		S3Bucket:    bucket,
		ExecutionMs: elapsed,
	})
}

func header(event events.APIGatewayV2HTTPRequest, name string) string {
	if v, ok := event.Headers[name]; ok {
		return v
	}
	upper := name
	if v, ok := event.Headers[upper]; ok {
		return v
	}
	return ""
}

func respond(statusCode int, resp QueryResponse) (events.APIGatewayV2HTTPResponse, error) {
	body, _ := json.Marshal(resp)
	return events.APIGatewayV2HTTPResponse{
		StatusCode: statusCode,
		Headers: map[string]string{
			"Content-Type": "application/json",
		},
		Body: string(body),
	}, nil
}
