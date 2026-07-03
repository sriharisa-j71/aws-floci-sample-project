CREATE TABLE IF NOT EXISTS public.emp (
    emp_id     SERIAL PRIMARY KEY,
    emp_name   VARCHAR(100) NOT NULL,
    department VARCHAR(100),
    salary     NUMERIC(10,2),
    hire_date  DATE
);
