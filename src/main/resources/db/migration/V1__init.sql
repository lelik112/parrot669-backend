CREATE TABLE profiles (
    id                UUID PRIMARY KEY,
    parrot_id         VARCHAR(32) NOT NULL UNIQUE,
    display_name      VARCHAR(120) NOT NULL,
    contact           VARCHAR(200) NOT NULL,
    access_token_hash CHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE TABLE properties (
    id          UUID PRIMARY KEY,
    profile_id  UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    title       VARCHAR(160) NOT NULL,
    city        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX properties_profile_id_idx ON properties(profile_id);

CREATE TABLE external_listings (
    id          UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    platform    VARCHAR(32) NOT NULL,
    url         TEXT NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT external_listings_platform_chk CHECK (platform IN ('airbnb'))
);

CREATE INDEX external_listings_property_id_idx ON external_listings(property_id);

CREATE TABLE verification_challenges (
    id                   UUID PRIMARY KEY,
    listing_id           UUID NOT NULL REFERENCES external_listings(id) ON DELETE CASCADE,
    kind                 VARCHAR(64) NOT NULL,
    block_date_1         DATE NOT NULL,
    block_date_2         DATE NOT NULL,
    leave_available_date DATE NOT NULL,
    status               VARCHAR(24) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    verified_at          TIMESTAMPTZ NULL,
    CONSTRAINT verification_challenges_kind_chk CHECK (kind IN ('calendar_block')),
    CONSTRAINT verification_challenges_status_chk CHECK (status IN ('pending', 'passed', 'expired', 'failed'))
);

CREATE INDEX verification_challenges_listing_id_idx
    ON verification_challenges(listing_id);

CREATE TABLE verifications (
    id            UUID PRIMARY KEY,
    profile_id    UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    listing_id    UUID NULL REFERENCES external_listings(id) ON DELETE CASCADE,
    claim         VARCHAR(64) NOT NULL,
    method        VARCHAR(64) NOT NULL,
    verified_at   TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NULL,
    challenge_id  UUID NULL UNIQUE REFERENCES verification_challenges(id) ON DELETE SET NULL,
    CONSTRAINT verifications_claim_chk CHECK (claim IN ('controls_listing')),
    CONSTRAINT verifications_method_chk CHECK (method IN ('calendar_challenge'))
);

CREATE INDEX verifications_profile_id_idx ON verifications(profile_id);
CREATE INDEX verifications_listing_id_idx ON verifications(listing_id);
CREATE INDEX verifications_expires_at_idx ON verifications(expires_at);
