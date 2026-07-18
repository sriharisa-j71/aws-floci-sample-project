package main

import (
	"github.com/aws/aws-lambda-go/lambda"
)

func main() {
	initDB()
	initS3()
	lambda.Start(handleRequest)
}
