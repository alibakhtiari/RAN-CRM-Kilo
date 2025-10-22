-- Shared Contact CRM Supabase schema and policies
-- Generated from Instructions.md, tailored for single-organization setup

-- Ensure required extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------- organizations (optional) ----------
CREATE TABLE IF NOT EXISTS organizations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  created_at timestamptz DEFAULT now()
);

-- ---------- user profiles (extends auth.users) ----------
CREATE TABLE IF NOT EXISTS profiles (
  id uuid PRIMARY KEY REFERENCES auth.users ON DELETE CASCADE,
  email text UNIQUE,
  display_name text,
  role text CHECK (role IN ('admin','user')) DEFAULT 'user',
  org_id uuid REFERENCES organizations(id),
  created_at timestamptz DEFAULT now()
);

-- ---------- contacts ----------
CREATE TABLE IF NOT EXISTS contacts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid REFERENCES organizations(id) NOT NULL,
  name text NOT NULL,
  phone_raw text NOT NULL,
  phone_normalized text NOT NULL,
  created_by uuid REFERENCES profiles(id) NOT NULL,
  created_at timestamptz DEFAULT now(),
  updated_by uuid REFERENCES profiles(id),
  updated_at timestamptz DEFAULT now(),
  version int DEFAULT 1
);

-- enforce uniqueness per org on normalized phone
CREATE UNIQUE INDEX IF NOT EXISTS contacts_org_phone_unique ON contacts (org_id, phone_normalized);

-- ---------- calls ----------
CREATE TABLE IF NOT EXISTS calls (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid REFERENCES organizations(id) NOT NULL,
  contact_id uuid REFERENCES contacts(id),
  phone_raw text NOT NULL,
  phone_normalized text NOT NULL,
  user_id uuid REFERENCES profiles(id) NOT NULL,
  direction text CHECK (direction IN ('incoming','outgoing')) NOT NULL,
  start_time timestamptz NOT NULL,
  duration_seconds int DEFAULT 0,
  created_at timestamptz DEFAULT now(),
  version int DEFAULT 1
);

-- indexes for quick lookup
CREATE INDEX IF NOT EXISTS calls_org_contact_idx ON calls (org_id, contact_id);
CREATE INDEX IF NOT EXISTS calls_org_phone_idx ON calls (org_id, phone_normalized);

-- ---------- sync_changes (optional) ----------
CREATE TABLE IF NOT EXISTS sync_changes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL,
  entity_type text CHECK(entity_type IN ('contact','call')) NOT NULL,
  entity_id uuid NOT NULL,
  change_type text CHECK(change_type IN ('insert','update','delete')) NOT NULL,
  changed_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS sync_changes_org_idx ON sync_changes (org_id, changed_at);

-- ---------- Trigger & Function: Prevent newer contact insertion when older exists ----------
CREATE OR REPLACE FUNCTION contacts_prevent_newer_duplicate()
RETURNS TRIGGER AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM contacts
    WHERE org_id = NEW.org_id
      AND phone_normalized = NEW.phone_normalized
  ) THEN
    PERFORM 1 FROM contacts
      WHERE org_id = NEW.org_id
        AND phone_normalized = NEW.phone_normalized
        AND created_at <= NEW.created_at
      LIMIT 1;
    IF FOUND THEN
      RAISE EXCEPTION 'Duplicate phone exists and is older or equal; insert rejected';
    END IF;
    DELETE FROM contacts
      WHERE org_id = NEW.org_id
        AND phone_normalized = NEW.phone_normalized
        AND created_at > NEW.created_at;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Attach triggers
DROP TRIGGER IF EXISTS trg_contacts_prevent_newer_duplicate ON contacts;
CREATE TRIGGER trg_contacts_prevent_newer_duplicate
BEFORE INSERT ON contacts
FOR EACH ROW EXECUTE FUNCTION contacts_prevent_newer_duplicate();

DROP TRIGGER IF EXISTS trg_contacts_prevent_newer_duplicate_update ON contacts;
CREATE TRIGGER trg_contacts_prevent_newer_duplicate_update
BEFORE UPDATE ON contacts
FOR EACH ROW
WHEN (OLD.phone_normalized IS DISTINCT FROM NEW.phone_normalized OR OLD.created_at IS DISTINCT FROM NEW.created_at)
EXECUTE FUNCTION contacts_prevent_newer_duplicate();

-- ---------- Row-Level Security Policies ----------
ALTER TABLE contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;

-- Helper function to get current profile
CREATE OR REPLACE FUNCTION current_profile() RETURNS profiles AS $$
  SELECT * FROM profiles WHERE id = auth.uid();
$$ LANGUAGE sql STABLE;

-- CONTACTS: SELECT — allow users in same org
CREATE POLICY select_contacts ON contacts
  FOR SELECT
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- CONTACTS: INSERT — owner or admin
CREATE POLICY insert_contacts ON contacts
  FOR INSERT
  WITH CHECK (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin')
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- CONTACTS: UPDATE — owner or admin
CREATE POLICY update_contacts ON contacts
  FOR UPDATE
  USING (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin')
  WITH CHECK (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');

-- CONTACTS: DELETE — owner or admin
CREATE POLICY delete_contacts ON contacts
  FOR DELETE
  USING (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');

-- CALLS: SELECT — allow users in same org
CREATE POLICY select_calls ON calls
  FOR SELECT
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- CALLS: INSERT — user or admin
CREATE POLICY insert_calls ON calls
  FOR INSERT
  WITH CHECK (user_id = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin')
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- PROFILES: admin-only
CREATE POLICY select_profiles ON profiles
  FOR SELECT
  USING ((SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');
CREATE POLICY insert_profiles ON profiles
  FOR INSERT
  USING ((SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');
CREATE POLICY delete_profiles ON profiles
  FOR DELETE
  USING ((SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');

-- ---------- Seed single organization (optional) ----------
-- INSERT INTO organizations (name) VALUES ('Default Organization');
-- After insert, set ORG_ID in android/local.properties

-- End of schema