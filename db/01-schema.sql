CREATE TABLE IF NOT EXISTS public.investors (
    customer_id       SERIAL PRIMARY KEY,
    full_name         VARCHAR(100) NOT NULL,
    annual_income_usd NUMERIC(12,2) NOT NULL,
    customer_segment  VARCHAR(20) NOT NULL,
    domicile_currency VARCHAR(3) NOT NULL,
    join_date         DATE NOT NULL DEFAULT CURRENT_DATE,
    bank_accounts     JSONB,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_investors_income_positive CHECK (annual_income_usd > 0),
    CONSTRAINT chk_investors_segment CHECK (customer_segment IN ('Retail', 'Premium', 'Wealth', 'Corporate')),
    CONSTRAINT chk_investors_currency CHECK (domicile_currency ~ '^[A-Z]{3}$')
);

CREATE TABLE IF NOT EXISTS public.personal_details (
    detail_id         SERIAL PRIMARY KEY,
    customer_id       INTEGER NOT NULL UNIQUE REFERENCES public.investors(customer_id),
    email             VARCHAR(100),
    phone             VARCHAR(20),
    date_of_birth     DATE,
    gender            VARCHAR(20),
    employment_status VARCHAR(20),
    address           JSONB,

    CONSTRAINT chk_details_email CHECK (email IS NULL OR email LIKE '%@%.%'),
    CONSTRAINT chk_details_dob_past CHECK (date_of_birth IS NULL OR date_of_birth < CURRENT_DATE),
    CONSTRAINT chk_details_gender CHECK (gender IS NULL OR gender IN ('Male', 'Female', 'Non-Binary', 'Other')),
    CONSTRAINT chk_details_emp_status CHECK (employment_status IS NULL OR employment_status IN ('Employed', 'Self-Employed', 'Retired', 'Student', 'Unemployed'))
);

CREATE TABLE IF NOT EXISTS public.risk_profiles (
    risk_id             SERIAL PRIMARY KEY,
    customer_id         INTEGER NOT NULL REFERENCES public.investors(customer_id),
    risk_profile        VARCHAR(20) NOT NULL,
    risk_score          INT,
    effective_start_date DATE NOT NULL,
    effective_end_date  DATE,
    is_active           BOOLEAN DEFAULT true,

    CONSTRAINT chk_risk_profile_type CHECK (risk_profile IN ('Conservative', 'Moderate', 'Aggressive')),
    CONSTRAINT chk_risk_score CHECK (risk_score IS NULL OR (risk_score >= 1 AND risk_score <= 100)),
    CONSTRAINT chk_risk_dates CHECK (effective_start_date <= effective_end_date OR effective_end_date IS NULL),
    CONSTRAINT chk_risk_active CHECK (is_active = (effective_end_date IS NULL))
);

CREATE TABLE IF NOT EXISTS public.investments (
    investment_id       SERIAL PRIMARY KEY,
    customer_id         INTEGER NOT NULL REFERENCES public.investors(customer_id),
    type                VARCHAR(30) NOT NULL,
    ticker              VARCHAR(20),
    description         VARCHAR(200),
    quantity            NUMERIC(14,4) NOT NULL,
    purchase_price_usd  NUMERIC(14,2) NOT NULL,
    purchase_price_dom  NUMERIC(14,2) NOT NULL,
    current_value_usd   NUMERIC(14,2) NOT NULL,
    current_value_dom   NUMERIC(14,2) NOT NULL,
    valuation_date      DATE NOT NULL,
    effective_start_date DATE NOT NULL,
    effective_end_date  DATE,
    is_active           BOOLEAN DEFAULT true,

    CONSTRAINT chk_inv_type CHECK (type IN ('Stocks', 'Bonds', 'ETF', 'Mutual Funds', 'Real Estate', 'Crypto', 'Commodities', 'Fixed Deposit')),
    CONSTRAINT chk_inv_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_inv_purchase_price_positive CHECK (purchase_price_usd > 0 AND purchase_price_dom > 0),
    CONSTRAINT chk_inv_current_value_nonneg CHECK (current_value_usd >= 0 AND current_value_dom >= 0),
    CONSTRAINT chk_inv_dates CHECK (effective_start_date <= effective_end_date OR effective_end_date IS NULL),
    CONSTRAINT chk_inv_active CHECK (is_active = (effective_end_date IS NULL))
);

CREATE TABLE IF NOT EXISTS public.liabilities (
    liability_id          SERIAL PRIMARY KEY,
    customer_id           INTEGER NOT NULL REFERENCES public.investors(customer_id),
    type                  VARCHAR(30) NOT NULL,
    creditor              VARCHAR(100),
    total_amount_usd      NUMERIC(14,2) NOT NULL,
    total_amount_dom      NUMERIC(14,2) NOT NULL,
    outstanding_amount_usd NUMERIC(14,2) NOT NULL,
    outstanding_amount_dom NUMERIC(14,2) NOT NULL,
    interest_rate         NUMERIC(5,3),
    monthly_payment_usd   NUMERIC(10,2),
    monthly_payment_dom   NUMERIC(10,2),
    effective_start_date  DATE NOT NULL,
    effective_end_date    DATE,
    is_active             BOOLEAN DEFAULT true,

    CONSTRAINT chk_liab_type CHECK (type IN ('Mortgage', 'Auto Loan', 'Personal Loan', 'Student Loan', 'Credit Card', 'Business Loan', 'Other')),
    CONSTRAINT chk_liab_total_positive CHECK (total_amount_usd > 0 AND total_amount_dom > 0),
    CONSTRAINT chk_liab_outstanding_nonneg CHECK (outstanding_amount_usd >= 0 AND outstanding_amount_dom >= 0),
    CONSTRAINT chk_liab_outstanding_lte_total CHECK (outstanding_amount_usd <= total_amount_usd AND outstanding_amount_dom <= total_amount_dom),
    CONSTRAINT chk_liab_rate_nonneg CHECK (interest_rate IS NULL OR interest_rate >= 0),
    CONSTRAINT chk_liab_payment_positive CHECK (monthly_payment_usd IS NULL OR monthly_payment_usd > 0),
    CONSTRAINT chk_liab_dates CHECK (effective_start_date <= effective_end_date OR effective_end_date IS NULL),
    CONSTRAINT chk_liab_active CHECK (is_active = (effective_end_date IS NULL))
);

CREATE TABLE IF NOT EXISTS public.currency_rates (
    rate_id         SERIAL PRIMARY KEY,
    base_currency   VARCHAR(3) NOT NULL,
    target_currency VARCHAR(3) NOT NULL,
    rate            NUMERIC(14,6) NOT NULL,
    effective_date  DATE NOT NULL DEFAULT CURRENT_DATE,

    CONSTRAINT chk_cr_rate_positive CHECK (rate > 0),
    CONSTRAINT chk_cr_base CHECK (base_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_cr_target CHECK (target_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_cr_pair UNIQUE (base_currency, target_currency, effective_date)
);
