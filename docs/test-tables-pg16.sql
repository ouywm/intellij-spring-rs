-- ============================================================
-- PostgreSQL 16 — Full Type Coverage Test Tables
-- For sea-orm codegen compatibility testing
-- No foreign keys, multi-schema
-- ============================================================

-- ── Schemas ──
CREATE SCHEMA IF NOT EXISTS test_basic;
CREATE SCHEMA IF NOT EXISTS test_advanced;
CREATE SCHEMA IF NOT EXISTS test_pk;

-- ── Custom enum types (in test_basic schema) ──
CREATE TYPE test_basic.mood AS ENUM ('happy', 'sad', 'neutral', 'angry');
CREATE TYPE test_basic.order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');
CREATE TYPE test_basic.priority_level AS ENUM ('low', 'medium', 'high', 'urgent');

-- ════════════════════════════════════════════════════════════
-- Schema: test_basic — common types
-- ════════════════════════════════════════════════════════════

-- Table 1: Numeric types
CREATE TABLE test_basic.t_type_numeric (
    id              serial          PRIMARY KEY,
    col_smallint    smallint        NOT NULL DEFAULT 0,
    col_integer     integer         NOT NULL DEFAULT 0,
    col_bigint      bigint          NOT NULL DEFAULT 0,
    col_decimal     decimal(20, 6)  NOT NULL DEFAULT 0,
    col_numeric     numeric(10, 2)  NOT NULL DEFAULT 0,
    col_real        real            NOT NULL DEFAULT 0,
    col_double      double precision NOT NULL DEFAULT 0,
    col_smallserial smallserial,
    col_bigserial   bigserial,
    col_int2        int2,
    col_int4        int4,
    col_int8        int8,
    col_float4      float4,
    col_float8      float8
);
COMMENT ON TABLE test_basic.t_type_numeric IS 'Numeric types test';
COMMENT ON COLUMN test_basic.t_type_numeric.col_smallint IS 'smallint (-32768 to 32767)';
COMMENT ON COLUMN test_basic.t_type_numeric.col_bigint IS 'bigint (-9223372036854775808 to 9223372036854775807)';
COMMENT ON COLUMN test_basic.t_type_numeric.col_decimal IS 'decimal with precision 20 scale 6';
COMMENT ON COLUMN test_basic.t_type_numeric.col_real IS 'single precision floating-point';
COMMENT ON COLUMN test_basic.t_type_numeric.col_double IS 'double precision floating-point';

-- Table 2: Character & binary types
CREATE TABLE test_basic.t_type_string (
    id              serial          PRIMARY KEY,
    col_char        char(10)        NOT NULL DEFAULT '',
    col_char_n      character(20),
    col_varchar     varchar(255)    NOT NULL DEFAULT '',
    col_varchar_n   character varying(500),
    col_text        text            NOT NULL DEFAULT '',
    col_bytea       bytea,
    col_name        name,
    col_bpchar      bpchar
);
COMMENT ON TABLE test_basic.t_type_string IS 'Character and binary types test';
COMMENT ON COLUMN test_basic.t_type_string.col_char IS 'fixed-length character';
COMMENT ON COLUMN test_basic.t_type_string.col_varchar IS 'variable-length character with limit';
COMMENT ON COLUMN test_basic.t_type_string.col_text IS 'unlimited text';
COMMENT ON COLUMN test_basic.t_type_string.col_bytea IS 'binary data';

-- Table 3: Date/Time types
CREATE TABLE test_basic.t_type_datetime (
    id                  serial          PRIMARY KEY,
    col_timestamp       timestamp       NOT NULL DEFAULT now(),
    col_timestamp_p     timestamp(3),
    col_timestamptz     timestamptz     NOT NULL DEFAULT now(),
    col_timestamptz_p   timestamptz(3),
    col_date            date            NOT NULL DEFAULT CURRENT_DATE,
    col_time            time            NOT NULL DEFAULT CURRENT_TIME,
    col_time_p          time(3),
    col_timetz          timetz,
    col_timetz_p        timetz(3),
    col_interval        interval
);
COMMENT ON TABLE test_basic.t_type_datetime IS 'Date and time types test';
COMMENT ON COLUMN test_basic.t_type_datetime.col_timestamp IS 'timestamp without time zone';
COMMENT ON COLUMN test_basic.t_type_datetime.col_timestamptz IS 'timestamp with time zone';
COMMENT ON COLUMN test_basic.t_type_datetime.col_date IS 'calendar date';
COMMENT ON COLUMN test_basic.t_type_datetime.col_time IS 'time of day without time zone';
COMMENT ON COLUMN test_basic.t_type_datetime.col_timetz IS 'time of day with time zone';
COMMENT ON COLUMN test_basic.t_type_datetime.col_interval IS 'time interval';

-- Table 4: Boolean, UUID, JSON, XML
CREATE TABLE test_basic.t_type_special (
    id              serial          PRIMARY KEY,
    col_bool        boolean         NOT NULL DEFAULT false,
    col_uuid        uuid            NOT NULL DEFAULT gen_random_uuid(),
    col_json        json,
    col_jsonb       jsonb           NOT NULL DEFAULT '{}',
    col_xml         xml
);
COMMENT ON TABLE test_basic.t_type_special IS 'Boolean, UUID, JSON, XML types test';
COMMENT ON COLUMN test_basic.t_type_special.col_bool IS 'boolean true/false';
COMMENT ON COLUMN test_basic.t_type_special.col_uuid IS 'universally unique identifier';
COMMENT ON COLUMN test_basic.t_type_special.col_json IS 'JSON text storage';
COMMENT ON COLUMN test_basic.t_type_special.col_jsonb IS 'JSON binary storage (indexable)';
COMMENT ON COLUMN test_basic.t_type_special.col_xml IS 'XML data';

-- Table 5: Enum types
CREATE TABLE test_basic.t_type_enum (
    id              serial                  PRIMARY KEY,
    col_mood        test_basic.mood         NOT NULL DEFAULT 'neutral',
    col_status      test_basic.order_status NOT NULL DEFAULT 'pending',
    col_priority    test_basic.priority_level
);
COMMENT ON TABLE test_basic.t_type_enum IS 'Custom enum types test';

-- Table 6: Nullable vs NOT NULL coverage
CREATE TABLE test_basic.t_nullable_test (
    id              serial      PRIMARY KEY,
    required_str    varchar(100) NOT NULL,
    optional_str    varchar(100),
    required_int    integer     NOT NULL DEFAULT 0,
    optional_int    integer,
    required_bool   boolean     NOT NULL DEFAULT true,
    optional_bool   boolean,
    required_ts     timestamptz NOT NULL DEFAULT now(),
    optional_ts     timestamptz,
    required_uuid   uuid        NOT NULL DEFAULT gen_random_uuid(),
    optional_uuid   uuid,
    required_jsonb  jsonb       NOT NULL DEFAULT '{}',
    optional_jsonb  jsonb,
    required_decimal numeric(10,2) NOT NULL DEFAULT 0,
    optional_decimal numeric(10,2)
);
COMMENT ON TABLE test_basic.t_nullable_test IS 'Nullable vs NOT NULL wrapping test';

-- ════════════════════════════════════════════════════════════
-- Schema: test_advanced — uncommon / advanced types
-- ════════════════════════════════════════════════════════════

-- Table 7: Array types
CREATE TABLE test_advanced.t_type_array (
    id              serial          PRIMARY KEY,
    col_int_arr     integer[]       NOT NULL DEFAULT '{}',
    col_text_arr    text[]          NOT NULL DEFAULT '{}',
    col_bool_arr    boolean[],
    col_float_arr   double precision[],
    col_varchar_arr varchar(100)[],
    col_uuid_arr    uuid[],
    col_jsonb_arr   jsonb[],
    col_int2d_arr   integer[][]
);
COMMENT ON TABLE test_advanced.t_type_array IS 'Array types test';
COMMENT ON COLUMN test_advanced.t_type_array.col_int_arr IS 'integer array';
COMMENT ON COLUMN test_advanced.t_type_array.col_text_arr IS 'text array';
COMMENT ON COLUMN test_advanced.t_type_array.col_int2d_arr IS 'two-dimensional integer array';

-- Table 8: Network types
CREATE TABLE test_advanced.t_type_network (
    id              serial          PRIMARY KEY,
    col_inet        inet,
    col_cidr        cidr,
    col_macaddr     macaddr,
    col_macaddr8    macaddr8
);
COMMENT ON TABLE test_advanced.t_type_network IS 'Network address types test';
COMMENT ON COLUMN test_advanced.t_type_network.col_inet IS 'IPv4 or IPv6 host address';
COMMENT ON COLUMN test_advanced.t_type_network.col_cidr IS 'IPv4 or IPv6 network address';
COMMENT ON COLUMN test_advanced.t_type_network.col_macaddr IS 'MAC address (6 bytes)';
COMMENT ON COLUMN test_advanced.t_type_network.col_macaddr8 IS 'MAC address (8 bytes, EUI-64)';

-- Table 9: Geometric types
CREATE TABLE test_advanced.t_type_geometric (
    id              serial          PRIMARY KEY,
    col_point       point,
    col_line        line,
    col_lseg        lseg,
    col_box         box,
    col_path        path,
    col_polygon     polygon,
    col_circle      circle
);
COMMENT ON TABLE test_advanced.t_type_geometric IS 'Geometric types test';

-- Table 10: Bit string & text search types
CREATE TABLE test_advanced.t_type_bit_search (
    id              serial          PRIMARY KEY,
    col_bit         bit(8),
    col_bit_var     bit varying(64),
    col_tsvector    tsvector,
    col_tsquery     tsquery
);
COMMENT ON TABLE test_advanced.t_type_bit_search IS 'Bit string and text search types test';
COMMENT ON COLUMN test_advanced.t_type_bit_search.col_bit IS 'fixed-length bit string';
COMMENT ON COLUMN test_advanced.t_type_bit_search.col_bit_var IS 'variable-length bit string';
COMMENT ON COLUMN test_advanced.t_type_bit_search.col_tsvector IS 'text search document';
COMMENT ON COLUMN test_advanced.t_type_bit_search.col_tsquery IS 'text search query';

-- Table 11: Range types (PG 14+ multirange included)
CREATE TABLE test_advanced.t_type_range (
    id                  serial          PRIMARY KEY,
    col_int4range       int4range,
    col_int8range       int8range,
    col_numrange        numrange,
    col_tsrange         tsrange,
    col_tstzrange       tstzrange,
    col_daterange       daterange,
    col_int4multirange  int4multirange,
    col_int8multirange  int8multirange,
    col_nummultirange   nummultirange,
    col_tsmultirange    tsmultirange,
    col_tstzmultirange  tstzmultirange,
    col_datemultirange  datemultirange
);
COMMENT ON TABLE test_advanced.t_type_range IS 'Range and multirange types test (PG 14+)';

-- Table 12: OID and system types
CREATE TABLE test_advanced.t_type_system (
    id              serial          PRIMARY KEY,
    col_oid         oid,
    col_pg_lsn      pg_lsn,
    col_money       money
);
COMMENT ON TABLE test_advanced.t_type_system IS 'System and monetary types test';
COMMENT ON COLUMN test_advanced.t_type_system.col_oid IS 'object identifier';
COMMENT ON COLUMN test_advanced.t_type_system.col_pg_lsn IS 'log sequence number';
COMMENT ON COLUMN test_advanced.t_type_system.col_money IS 'monetary amount';

-- ════════════════════════════════════════════════════════════
-- Schema: test_pk — primary key variants
-- ════════════════════════════════════════════════════════════

CREATE TABLE test_pk.t_pk_serial (
    id      serial      PRIMARY KEY,
    name    varchar(50) NOT NULL
);
COMMENT ON TABLE test_pk.t_pk_serial IS 'serial (int4) primary key test';

CREATE TABLE test_pk.t_pk_bigserial (
    id      bigserial   PRIMARY KEY,
    name    varchar(50) NOT NULL
);
COMMENT ON TABLE test_pk.t_pk_bigserial IS 'bigserial (int8) primary key test';

CREATE TABLE test_pk.t_pk_uuid (
    id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name    varchar(50) NOT NULL
);
COMMENT ON TABLE test_pk.t_pk_uuid IS 'UUID primary key test';

CREATE TABLE test_pk.t_pk_text (
    code    varchar(30) PRIMARY KEY,
    name    varchar(50) NOT NULL
);
COMMENT ON TABLE test_pk.t_pk_text IS 'varchar primary key test';

CREATE TABLE test_pk.t_pk_composite (
    tenant_id   integer     NOT NULL,
    user_id     integer     NOT NULL,
    name        varchar(50) NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);
COMMENT ON TABLE test_pk.t_pk_composite IS 'Composite primary key test';

-- ════════════════════════════════════════════════════════════
-- Cleanup (run to drop everything)
-- ════════════════════════════════════════════════════════════
-- DROP SCHEMA test_basic CASCADE;
-- DROP SCHEMA test_advanced CASCADE;
-- DROP SCHEMA test_pk CASCADE;