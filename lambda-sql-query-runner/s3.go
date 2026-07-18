package main

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

var s3Client *s3.Client

func initS3() {
	ctx := context.Background()
	cfg, err := awsconfig.LoadDefaultConfig(ctx)
	if err != nil {
		panic(fmt.Sprintf("aws config: %v", err))
	}

	endpoint := os.Getenv("AWS_ENDPOINT_URL")
	if endpoint != "" {
		s3Client = s3.NewFromConfig(cfg, func(o *s3.Options) {
			o.BaseEndpoint = &endpoint
			o.UsePathStyle = true
		})
	} else {
		s3Client = s3.NewFromConfig(cfg)
	}
}

func uploadToS3(ctx context.Context, bucket, key, content string) error {
	_, err := s3Client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:      aws.String(bucket),
		Key:         aws.String(key),
		Body:        strings.NewReader(content),
		ContentType: aws.String("text/plain"),
	})
	if err != nil {
		return fmt.Errorf("s3 put object: %w", err)
	}
	return nil
}

func buildS3Key(prefix, queryName string) string {
	date := time.Now().UTC().Format("2006-01-02")
	ts := time.Now().UTC().UnixMilli()
	name := strings.ReplaceAll(queryName, " ", "_")
	return fmt.Sprintf("%s/%s/%s_%d.txt", prefix, date, name, ts)
}
