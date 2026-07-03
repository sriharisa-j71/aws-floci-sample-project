WITH investor_rows AS (
    INSERT INTO public.investors (full_name, annual_income_usd, customer_segment, domicile_currency, join_date, bank_accounts)
    VALUES
    ('Alice Johnson', 120000.00, 'Wealth', 'USD', '2020-03-15',
        '[{"bank_name":"Chase","account_type":"Checking","balance":45000,"currency":"USD"},{"bank_name":"Chase","account_type":"Savings","balance":210000,"currency":"USD"},{"bank_name":"Morgan Stanley","account_type":"Brokerage","balance":750000,"currency":"USD"}]'),
    ('Bob Smith', 85000.00, 'Premium', 'USD', '2021-07-01',
        '[{"bank_name":"Bank of America","account_type":"Checking","balance":12000,"currency":"USD"},{"bank_name":"Bank of America","account_type":"Savings","balance":85000,"currency":"USD"}]'),
    ('Carol Davis', 200000.00, 'Wealth', 'CHF', '2019-01-10',
        '[{"bank_name":"Goldman Sachs","account_type":"Checking","balance":95000,"currency":"USD"},{"bank_name":"Goldman Sachs","account_type":"Savings","balance":450000,"currency":"USD"},{"bank_name":"Credit Suisse","account_type":"Fixed Deposit","balance":500000,"currency":"CHF"}]'),
    ('Dave Wilson', 65000.00, 'Retail', 'USD', '2022-11-20',
        '[{"bank_name":"Wells Fargo","account_type":"Checking","balance":5500,"currency":"USD"},{"bank_name":"Wells Fargo","account_type":"Savings","balance":18000,"currency":"USD"}]'),
    ('Eve Martin', 175000.00, 'Corporate', 'GBP', '2024-02-28',
        '[{"bank_name":"Citi","account_type":"Checking","balance":78000,"currency":"USD"},{"bank_name":"Citi","account_type":"Savings","balance":320000,"currency":"USD"},{"bank_name":"HSBC","account_type":"Fixed Deposit","balance":250000,"currency":"GBP"},{"bank_name":"Interactive Brokers","account_type":"Brokerage","balance":1200000,"currency":"USD"}]')
    RETURNING customer_id, full_name, domicile_currency
), ordered AS (
    SELECT customer_id, domicile_currency, ROW_NUMBER() OVER (ORDER BY customer_id) AS rn
    FROM investor_rows
)
INSERT INTO public.personal_details (customer_id, email, phone, date_of_birth, gender, employment_status, address)
SELECT o.customer_id, d.email, d.phone, d.dob, d.gender, d.emp_status, d.addr::jsonb
FROM ordered o
JOIN (VALUES
    (1, 'alice.johnson@email.com',  '+1-212-555-0147', '1985-06-12'::DATE, 'Female', 'Employed',    '{"street":"245 Park Avenue","city":"New York","state":"NY","zip":"10167","country":"US"}'),
    (2, 'bob.smith@email.com',      '+1-312-555-0293', '1990-11-03'::DATE, 'Male',   'Employed',    '{"street":"1313 N Lake Shore Dr","city":"Chicago","state":"IL","zip":"60610","country":"US"}'),
    (3, 'carol.davis@email.com',    '+1-415-555-0418', '1978-04-22'::DATE, 'Female', 'Self-Employed','{"street":"1 Market Street","city":"San Francisco","state":"CA","zip":"94105","country":"US"}'),
    (4, 'dave.wilson@email.com',    '+1-713-555-0367', '1995-09-15'::DATE, 'Male',   'Employed',    '{"street":"1200 Louisiana St","city":"Houston","state":"TX","zip":"77002","country":"US"}'),
    (5, 'eve.martin@email.com',     '+44-20-7946-0123','1982-01-28'::DATE, 'Female', 'Employed',    '{"street":"8 Canada Square","city":"London","state":"England","zip":"E14 5HQ","country":"UK"}')
) AS d(rn, email, phone, dob, gender, emp_status, addr) ON o.rn = d.rn;

-- Risk profiles (one active per investor)
INSERT INTO public.risk_profiles (customer_id, risk_profile, effective_start_date, effective_end_date, is_active)
SELECT customer_id, profile, start_date, end_date, active
FROM (
    SELECT customer_id FROM public.investors ORDER BY customer_id
) i
JOIN (VALUES
    (1, 'Moderate',    '2020-03-15'::DATE, NULL::DATE, true),
    (2, 'Conservative','2021-07-01'::DATE, NULL,       true),
    (3, 'Aggressive',  '2019-01-10'::DATE, NULL,       true),
    (4, 'Moderate',    '2022-11-20'::DATE, NULL,       true),
    (5, 'Aggressive',  '2024-02-28'::DATE, NULL,       true)
) AS d(rn, profile, start_date, end_date, active) ON i.customer_id = d.rn;

-- Investments
INSERT INTO public.investments (customer_id, type, ticker, description, quantity, purchase_price_usd, purchase_price_dom, current_value_usd, current_value_dom, valuation_date, effective_start_date, is_active)
SELECT i.customer_id, d.type, d.ticker, d."desc", d.qty, d.pp_usd, d.pp_dom, d.cv_usd, d.cv_dom, d.val_date, d.eff_date, true
FROM (SELECT customer_id, domicile_currency FROM public.investors ORDER BY customer_id) i
JOIN (VALUES
    (1, 'Stocks',      'AAPL',  'Apple Inc.',               500,  150.00, 150.00, 89000.00,  89000.00,  '2025-06-30'::DATE, '2020-03-15'::DATE),
    (1, 'Bonds',       'US10Y', '10-Year Treasury',         100,  1000.00,1000.00,98500.00,  98500.00,  '2025-06-30',       '2020-03-15'),
    (1, 'Mutual Funds','VTSAX', 'Vanguard Total Stock',     2000, 95.00,  95.00,  218000.00, 218000.00, '2025-06-30',       '2020-03-15'),
    (2, 'ETF',         'SPY',   'S&P 500 ETF',              150,  380.00, 380.00, 78000.00,  78000.00,  '2025-06-30',       '2021-07-01'),
    (2, 'Stocks',      'JNJ',   'Johnson & Johnson',        80,   155.00, 155.00, 13200.00,  13200.00,  '2025-06-30',       '2021-07-01'),
    (3, 'Stocks',      'AMZN',  'Amazon.com',               300,  3200.00,2848.00,570000.00, 507300.00, '2025-06-30',       '2019-01-10'),
    (3, 'Stocks',      'TSLA',  'Tesla Inc.',               400,  650.00, 578.50, 112000.00, 99680.00,  '2025-06-30',       '2021-04-01'),
    (3, 'Real Estate', 'REIT',  'Commercial Property Fund', 5000, 200.00, 178.00, 1100000.00,979000.00,'2025-06-30',       '2019-06-01'),
    (3, 'Bonds',       'EMB',   'Emerging Markets Bond ETF',200,  85.00,  75.65,  16200.00,  14418.00,  '2025-06-30',       '2022-01-15'),
    (4, 'Mutual Funds','VFIAX', 'Vanguard 500 Index Fund',  100,  320.00, 320.00, 38000.00,  38000.00,  '2025-06-30',       '2022-11-20'),
    (5, 'Stocks',      'GOOGL', 'Alphabet Inc.',            200,  2800.00,2212.00,360000.00, 284400.00, '2025-06-30',       '2024-02-28'),
    (5, 'Stocks',      'NVDA',  'NVIDIA Corporation',       150,  450.00, 355.50, 195000.00, 154050.00, '2025-06-30',       '2024-02-28'),
    (5, 'ETF',         'QQQ',   'Nasdaq-100 ETF',           500,  280.00, 221.20, 215000.00, 169850.00, '2025-06-30',       '2024-03-15'),
    (5, 'Real Estate', 'REIT',  'Residential Rental Fund',  3000, 150.00, 118.50, 480000.00, 379200.00, '2025-06-30',       '2024-04-01')
) AS d(rn, type, ticker, "desc", qty, pp_usd, pp_dom, cv_usd, cv_dom, val_date, eff_date)
ON i.customer_id = d.rn;

-- Liabilities
INSERT INTO public.liabilities (customer_id, type, creditor, total_amount_usd, total_amount_dom, outstanding_amount_usd, outstanding_amount_dom, interest_rate, monthly_payment_usd, monthly_payment_dom, effective_start_date, is_active)
SELECT i.customer_id, d.type, d.creditor, d.total_usd, d.total_dom, d.out_usd, d.out_dom, d.rate, d.monthly_usd, d.monthly_dom, d.eff_date, true
FROM (SELECT customer_id, domicile_currency FROM public.investors ORDER BY customer_id) i
JOIN (VALUES
    (1, 'Mortgage',    'Wells Fargo',     650000.00, 650000.00, 520000.00, 520000.00, 4.500, 3200.00, 3200.00, '2020-06-01'::DATE),
    (1, 'Credit Card', 'Amex',            15000.00,  15000.00,  4200.00,   4200.00,   18.500,420.00,  420.00,  '2020-03-15'),
    (2, 'Auto Loan',   'Toyota Financial', 35000.00,  35000.00,  22000.00,  22000.00,  3.900, 650.00,  650.00,  '2023-01-15'),
    (3, 'Mortgage',    'JP Morgan',       1200000.00,1068000.00,980000.00, 872200.00, 3.200, 5800.00, 5162.00, '2021-04-01'),
    (3, 'Personal Loan','Goldman Sachs',   100000.00, 89000.00,  45000.00,  40050.00,  6.800, 2500.00, 2225.00, '2023-09-01'),
    (4, 'Student Loan', 'Navient',         45000.00,  45000.00,  28000.00,  28000.00,  5.500, 480.00,  480.00,  '2018-08-15'),
    (4, 'Credit Card',  'Discover',         8000.00,   8000.00,   3200.00,   3200.00,  16.500,160.00,  160.00,  '2022-11-20'),
    (5, 'Mortgage',    'Citi Mortgage',    800000.00, 632000.00, 720000.00, 568800.00, 2.800, 4100.00, 3239.00, '2024-01-15')
) AS d(rn, type, creditor, total_usd, total_dom, out_usd, out_dom, rate, monthly_usd, monthly_dom, eff_date)
ON i.customer_id = d.rn;

-- Currency rates (USD as base)
INSERT INTO public.currency_rates (base_currency, target_currency, rate, effective_date) VALUES
    ('USD', 'USD', 1.000000, CURRENT_DATE),
    ('USD', 'GBP', 0.790000, CURRENT_DATE),
    ('USD', 'CHF', 0.890000, CURRENT_DATE),
    ('USD', 'EUR', 0.920000, CURRENT_DATE),
    ('USD', 'JPY', 158.500000, CURRENT_DATE);
