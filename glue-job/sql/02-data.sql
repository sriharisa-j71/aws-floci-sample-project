TRUNCATE TABLE public.emp RESTART IDENTITY CASCADE;

INSERT INTO public.emp (emp_name, department, salary, hire_date) VALUES
    ('Alice Johnson', 'Engineering', 95000.00, '2022-03-15'),
    ('Bob Smith',     'Marketing',   72000.00, '2021-07-01'),
    ('Carol Davis',   'Finance',     88000.00, '2023-01-10'),
    ('Dave Wilson',   'Engineering', 105000.00,'2020-11-20'),
    ('Eve Martin',    'HR',          65000.00, '2024-02-28');
