package main

import (
	"context"
	"fmt"
	"os"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
)

var dbPool *pgxpool.Pool

func initDB() {
	dsn := os.Getenv("DB_DSN")
	if dsn == "" {
		dsn = "postgres://admin:secret123@postgres:5432/postgres?sslmode=disable"
	}
	var err error
	dbPool, err = pgxpool.New(context.Background(), dsn)
	if err != nil {
		panic(fmt.Sprintf("db pool: %v", err))
	}
}

func buildQuery(qdef QueryDef, body QueryBody) (string, []any) {
	sb := strings.Builder{}
	sb.WriteString(qdef.SQL)

	args := make([]any, 0)
	paramIdx := len(body.Params) + 1

	for _, p := range body.Params {
		args = append(args, p)
	}

	if qdef.DynamicWhere && len(body.Where) > 0 {
		allowed := make(map[string]bool, len(qdef.Filters))
		for _, f := range qdef.Filters {
			allowed[f] = true
		}

		cols := make([]string, 0, len(body.Where))
		for col := range body.Where {
			if len(qdef.Filters) > 0 && !allowed[col] {
				continue
			}
			cols = append(cols, col)
		}
		sort.Strings(cols)

		for _, col := range cols {
			qualified := col
			if qdef.FilterMap != nil {
				if m, ok := qdef.FilterMap[col]; ok {
					qualified = m
				}
			} else if qdef.FilterPrefix != "" {
				qualified = qdef.FilterPrefix + col
			}
			val := body.Where[col]
			switch v := val.(type) {
			case []interface{}:
				if len(v) == 0 {
					continue
				}
				placeholders := make([]string, len(v))
				for i, elem := range v {
					placeholders[i] = fmt.Sprintf("$%d", paramIdx)
					args = append(args, elem)
					paramIdx++
				}
				fmt.Fprintf(&sb, " AND %s IN (%s)", qualified, strings.Join(placeholders, ", "))
			default:
				sb.WriteString(fmt.Sprintf(" AND %s = $%d", qualified, paramIdx))
				args = append(args, v)
				paramIdx++
			}
		}
	}

	if len(body.WhereList) > 0 {
		for _, clause := range body.WhereList {
			if clause.Condition == "" {
				continue
			}
			conj := strings.ToUpper(clause.Conjunction)
			if conj == "" {
				conj = "AND"
			}
			fmt.Fprintf(&sb, " %s %s", conj, clause.Condition)
		}
	}

	if qdef.Limit && body.Limit > 0 {
		fmt.Fprintf(&sb, " LIMIT %d", body.Limit)
	}
	if body.Offset > 0 {
		fmt.Fprintf(&sb, " OFFSET %d", body.Offset)
	}

	return sb.String(), args
}

type QueryResult struct {
	Columns []string
	Rows    [][]any
	Count   int
}

func executeQuery(ctx context.Context, sql string, args []any) (*QueryResult, error) {
	rows, err := dbPool.Query(ctx, sql, args...)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()

	fields := rows.FieldDescriptions()
	columns := make([]string, len(fields))
	for i, f := range fields {
		columns[i] = f.Name
	}

	var resultRows [][]any
	for rows.Next() {
		vals, err := rows.Values()
		if err != nil {
			return nil, fmt.Errorf("rows values: %w", err)
		}
		resultRows = append(resultRows, vals)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows err: %w", err)
	}

	return &QueryResult{
		Columns: columns,
		Rows:    resultRows,
		Count:   len(resultRows),
	}, nil
}

func formatOutput(result *QueryResult) string {
	sb := strings.Builder{}
	sb.WriteString(strings.Join(result.Columns, "|"))
	sb.WriteString("\n")

	for _, row := range result.Rows {
		vals := make([]string, len(row))
		for i, v := range row {
			vals[i] = fmt.Sprintf("%v", v)
		}
		sb.WriteString(strings.Join(vals, "|"))
		sb.WriteString("\n")
	}

	return sb.String()
}
