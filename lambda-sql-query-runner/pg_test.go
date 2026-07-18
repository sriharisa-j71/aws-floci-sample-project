package main

import (
	"strings"
	"testing"
)

func baseQueryDef(sql string) QueryDef {
	return QueryDef{
		Name:         "test",
		SQL:          sql,
		DynamicWhere: true,
		Limit:        true,
	}
}

func TestWhereList_SingleCondition(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "", Condition: "dept_no = 10"},
		},
	}

	sql, args := buildQuery(qdef, body)
	if !strings.Contains(sql, "AND dept_no = 10") {
		t.Errorf("expected AND dept_no = 10, got: %s", sql)
	}
	if len(args) != 0 {
		t.Errorf("expected 0 args, got %d", len(args))
	}
}

func TestWhereList_MultipleConditions_AND(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "", Condition: "dept_no = 10"},
			{Conjunction: "AND", Condition: "sal > 100000"},
			{Conjunction: "AND", Condition: "role = 'admin'"},
		},
	}

	sql, args := buildQuery(qdef, body)
	if !strings.Contains(sql, "AND dept_no = 10") {
		t.Errorf("missing first condition: %s", sql)
	}
	if !strings.Contains(sql, "AND sal > 100000") {
		t.Errorf("missing second condition: %s", sql)
	}
	if !strings.Contains(sql, "AND role = 'admin'") {
		t.Errorf("missing third condition: %s", sql)
	}
	if len(args) != 0 {
		t.Errorf("expected 0 args, got %d", len(args))
	}
}

func TestWhereList_MixedAND_OR(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "", Condition: "dept_no = 10"},
			{Conjunction: "AND", Condition: "sal > 100000"},
			{Conjunction: "OR", Condition: "role = 'admin'"},
		},
	}

	sql, _ := buildQuery(qdef, body)
	// Must contain OR
	if !strings.Contains(sql, "OR role = 'admin'") {
		t.Errorf("missing OR condition: %s", sql)
	}
	// Must contain both ANDs
	if !strings.Contains(sql, "AND dept_no = 10") {
		t.Errorf("missing first AND: %s", sql)
	}
	if !strings.Contains(sql, "AND sal > 100000") {
		t.Errorf("missing second AND: %s", sql)
	}
}

func TestWhereList_SkipsEmptyConditions(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "", Condition: ""},
			{Conjunction: "AND", Condition: "dept_no = 10"},
			{Conjunction: "AND", Condition: ""},
		},
	}

	sql, _ := buildQuery(qdef, body)
	if strings.Contains(sql, "AND  AND") {
		t.Errorf("empty conditions should be skipped: %s", sql)
	}
	if !strings.Contains(sql, "AND dept_no = 10") {
		t.Errorf("non-empty condition should be present: %s", sql)
	}
}

func TestWhereList_ConjunctionCaseInsensitive(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "and", Condition: "a = 1"},
			{Conjunction: "or", Condition: "b = 2"},
		},
	}

	sql, _ := buildQuery(qdef, body)
	if !strings.Contains(sql, "AND a = 1") {
		t.Errorf("lowercase 'and' should produce AND: %s", sql)
	}
	if !strings.Contains(sql, "OR b = 2") {
		t.Errorf("lowercase 'or' should produce OR: %s", sql)
	}
}

func TestWhereList_FirstExplicitOR(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "OR", Condition: "a = 1"},
		},
	}

	sql, _ := buildQuery(qdef, body)
	if !strings.Contains(sql, "OR a = 1") {
		t.Errorf("explicit OR on first clause should be preserved: %s", sql)
	}
}

func TestWhereList_WithParams(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE x = $1")
	body := QueryBody{
		Params: []any{"hello"},
		WhereList: []WhereClause{
			{Conjunction: "", Condition: "dept_no = 10"},
		},
	}

	sql, args := buildQuery(qdef, body)
	if !strings.Contains(sql, "WHERE x = $1") {
		t.Errorf("base param missing: %s", sql)
	}
	if !strings.Contains(sql, "AND dept_no = 10") {
		t.Errorf("where_list condition missing: %s", sql)
	}
	if len(args) != 1 {
		t.Errorf("expected 1 arg, got %d", len(args))
	}
	if args[0] != "hello" {
		t.Errorf("expected arg 'hello', got %v", args[0])
	}
}

func TestWhereList_BackwardCompat_WhereMapOnly(t *testing.T) {
	qdef := QueryDef{
		Name:         "test",
		SQL:          "SELECT * FROM t WHERE 1=1",
		DynamicWhere: true,
		Filters:      []string{"col_a", "col_b"},
		Limit:        true,
	}
	body := QueryBody{
		Where: map[string]any{
			"col_a": "val1",
		},
	}

	sql, args := buildQuery(qdef, body)
	if !strings.Contains(sql, "AND col_a = $1") {
		t.Errorf("where map condition missing: %s", sql)
	}
	if len(args) != 1 {
		t.Errorf("expected 1 arg, got %d", len(args))
	}
}

func TestWhereList_WithFilterMap(t *testing.T) {
	qdef := QueryDef{
		Name:         "test",
		SQL:          "SELECT * FROM t WHERE 1=1",
		DynamicWhere: true,
		Filters:      []string{"id", "name"},
		FilterMap:    map[string]string{"id": "t.id"},
		Limit:        true,
	}
	body := QueryBody{
		Where: map[string]any{
			"id": "CUST-001",
		},
		WhereList: []WhereClause{
			{Conjunction: "AND", Condition: "t.name LIKE '%test%'"},
		},
	}

	sql, args := buildQuery(qdef, body)
	// where map uses FilterMap
	if !strings.Contains(sql, "AND t.id = $1") {
		t.Errorf("FilterMap not applied: %s", sql)
	}
	// where_list appends raw
	if !strings.Contains(sql, "AND t.name LIKE '%test%'") {
		t.Errorf("where_list condition missing: %s", sql)
	}
	if len(args) != 1 {
		t.Errorf("expected 1 arg, got %d", len(args))
	}
}

func TestWhereList_WhereListEmpty(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{}

	sql, _ := buildQuery(qdef, body)
	if strings.Contains(sql, "AND") {
		t.Errorf("no conditions should be appended: %s", sql)
	}
}

func TestWhereList_LimitOffset(t *testing.T) {
	qdef := baseQueryDef("SELECT * FROM t WHERE 1=1")
	body := QueryBody{
		WhereList: []WhereClause{
			{Conjunction: "", Condition: "a = 1"},
		},
		Limit:  10,
		Offset: 5,
	}

	sql, _ := buildQuery(qdef, body)
	if !strings.Contains(sql, "LIMIT 10") {
		t.Errorf("LIMIT missing: %s", sql)
	}
	if !strings.Contains(sql, "OFFSET 5") {
		t.Errorf("OFFSET missing: %s", sql)
	}
	// LIMIT/OFFSET should come after WHERE conditions
	limitIdx := strings.Index(sql, "LIMIT")
	offsetIdx := strings.Index(sql, "OFFSET")
	condIdx := strings.Index(sql, "a = 1")
	if condIdx > limitIdx || condIdx > offsetIdx {
		t.Errorf("conditions should come before LIMIT/OFFSET: %s", sql)
	}
}
