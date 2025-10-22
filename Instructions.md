sNice — below is a concise, ready-to-use **PRD + Supabase database schema** tuned for a **Kotlin native Android** app that:

* uses **Supabase** (Auth + Postgres) — *no separate backend*;
* runs background sync (WorkManager / SyncAdapter where available) to sync **device Contacts** and **Call Logs** asynchronously;
* avoids duplicates by **normalizing phone numbers** (E.164) and enforcing uniqueness **server-side**;
* keeps the **older contact** when two users save the same number;
* exposes exactly **2 screens** (Contacts, Settings) and the described UX (call/message/WhatsApp, edit, call history).

I included:

1. PRD (behavior, sync rules, UX flow, background strategy)
2. Detailed Postgres schema for Supabase (tables + indexes)
3. RLS (Row Level Security) policy examples and database trigger to enforce "keep older contact" duplicate rule
4. Practical implementation notes: phone normalization, offline cache (Room), retry queue, conflict resolution, and improvements/suggestions.

---

# PRD — Shared Contact CRM (Kotlin Android + Supabase)

**Project name:** Shared Contact CRM — Native Android
**Platform:** Android (Kotlin)
**Backend:** Supabase (Auth + Postgres DB) — no custom backend
**Version:** v1.0

## Purpose

Mobile-first CRM for small teams: sync device contacts and call logs to a shared Supabase DB, allow shared visibility of call history, prevent duplicate phone numbers across users (older wins), and let admin manage users. App runs on Android only (Kotlin), performs background sync asynchronously, persists local state for offline operation, and retries failed syncs.

## Primary users / roles

* **Admin** — seeded into Supabase; can create/delete users (no public signup).
* **User** — assigned by admin; can view all shared contacts but can only edit/delete contacts they created.

## Must-have features

1. **Sync Contacts (bi-directional, asynchronous)**

   * Read device contacts; upload only new/changed contacts.
   * Download server contacts and write them to device (if not present).
   * Do not re-upload unchanged contacts.
   * Each contact stores `created_at` timestamp and `created_by_user_id`.
   * If user attempts to add a phone number that already exists (normalized), reject on server; client removes the local (newer) copy.
   * Compare domestic/international formats: client normalizes numbers to E.164 prior to upload (libphonenumber).
   * Retry failed uploads; exponential backoff.

2. **Sync Call Logs (one-way from device → server)**

   * Read incoming & outgoing phone numbers; push to Supabase.
   * Each call record: phone, normalized_phone, direction (in/out), start_time, duration, user_id.
   * Server stores calls; all users can view call history per contact.

3. **Contacts Screen (single main screen)**

   * Lists shared contacts (from local DB cache; synced with server).
   * Each contact row:

     * Name
     * Primary phone (display formatted)
     * Buttons: Call, Message (SMS), WhatsApp
     * 3-dot menu → Edit (only if current user is owner OR admin)
     * Tap → Contact detail showing contact info + **Call History** (synchronized): list of calls with date/time, who (user) & direction icon (in/out)
   * Duplicate detection: app only keeps one device contact per normalized phone; if duplicate found from user2 and user1 is older, remove user2’s contact locally.

4. **Settings Screen (second screen)**

   * Sync interval selection (e.g., manual, 15m, 30m, 1h, 6h)
   * Sync log (recent sync attempts, successes, failures with timestamps)
   * Admin section (visible for admin only): add / remove users (creates Supabase Auth user via Admin token)
   * Logout button

5. **Background behavior**

   * WorkManager (PeriodicWorkRequests) handles periodic background sync (contacts & calls).
   * Immediately schedule sync on boot, on connectivity change, and on app foreground.
   * Use a foreground service only if continuous background operation is required (API >26 restrictions).
   * Use SyncAdapter integration optionally for better OS integration; but primary background is WorkManager.

6. **Offline-first**

   * Local Room DB caches contacts & calls; writes are queued when offline and retried later.
   * Local change-tracking: `dirty` flag + `last_modified` timestamp to decide pushes.

## Non-functional requirements

* **Security:** HTTPS via Supabase client; use Supabase Auth (JWT) and RLS policies to ensure ownership rules.
* **Phone normalization:** Use libphonenumber (Kotlin) to produce E.164 `normalized_phone`.
* **Performance:** Batch uploads and downloads; incremental sync (since timestamp).
* **Scalability:** Support orgs with thousands of contacts; use indexed columns.
* **Privacy / Compliance:** Explicit runtime permissions for call log & contacts; in-app consent flow and privacy notice; option to disable call log syncing.

## Success criteria

* No mobile-visible duplicate phone numbers across users.
* Contacts sync converge across devices within the configured interval.
* Call logs are reliably uploaded within 1–2 sync cycles.
* Unsuccessful uploads are retried and surfaced in Sync Log.

---

# Database Schema for Supabase (Postgres)

Below is the SQL for tables, indexes, and triggers. After schema we provide recommended RLS policies.

> **Note:** Supabase Auth’s `auth.users` table exists and should be used for authentication. We add a `profiles` table to map user metadata (role, org_id). Replace `your_org_id` pattern with your org logic — this schema uses a single org model or optional `orgs` table if you want multi-org support.

```sql
-- ---------- organizations (optional) ----------
CREATE TABLE IF NOT EXISTS organizations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  created_at timestamptz DEFAULT now()
);

-- ---------- user profiles (extends auth.users) ----------
CREATE TABLE IF NOT EXISTS profiles (
  id uuid PRIMARY KEY REFERENCES auth.users ON DELETE CASCADE, -- same as auth user id
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
  phone_raw text NOT NULL,               -- phone as user entered (display)
  phone_normalized text NOT NULL,        -- E.164 normalized for uniqueness, e.g. +989123456789
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
  user_id uuid REFERENCES profiles(id) NOT NULL,  -- who made/received the call (uploader)
  direction text CHECK (direction IN ('incoming','outgoing')) NOT NULL,
  start_time timestamptz NOT NULL,
  duration_seconds int DEFAULT 0,
  created_at timestamptz DEFAULT now(),
  version int DEFAULT 1
);

-- index for quick lookup
CREATE INDEX IF NOT EXISTS calls_org_contact_idx ON calls (org_id, contact_id);
CREATE INDEX IF NOT EXISTS calls_org_phone_idx ON calls (org_id, phone_normalized);

-- ---------- sync_changes (optional) to support incremental sync by "since" timestamp ----------
CREATE TABLE IF NOT EXISTS sync_changes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL,
  entity_type text CHECK(entity_type IN ('contact','call')) NOT NULL,
  entity_id uuid NOT NULL,
  change_type text CHECK(change_type IN ('insert','update','delete')) NOT NULL,
  changed_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS sync_changes_org_idx ON sync_changes (org_id, changed_at);
```

---

# Trigger & Function: Prevent newer contact insertion when older exists

We want to **reject** an insert of a contact with the same normalized phone if an existing contact with that normalized phone and earlier `created_at` already exists. This ensures the older wins and prevents duplicates from different users.

```sql
-- Function checks on INSERT to contacts, prevents insert if older exists
CREATE OR REPLACE FUNCTION contacts_prevent_newer_duplicate()
RETURNS TRIGGER AS $$
BEGIN
  -- look for existing contact in same org with same normalized phone
  IF EXISTS (
    SELECT 1 FROM contacts
    WHERE org_id = NEW.org_id
      AND phone_normalized = NEW.phone_normalized
  ) THEN
    -- find earliest existing created_at
    PERFORM 1 FROM contacts
      WHERE org_id = NEW.org_id
        AND phone_normalized = NEW.phone_normalized
        AND created_at <= NEW.created_at
      LIMIT 1;
    -- if such older-or-equal exists, abort the insert
    IF FOUND THEN
      RAISE EXCEPTION 'Duplicate phone exists and is older or equal; insert rejected';
    END IF;
    -- if not found, it means existing has created_at > NEW.created_at (existing is newer)
    -- in that case, keep the older (NEW) and delete the existing newer one:
    -- delete existing newer contact(s)
    DELETE FROM contacts
      WHERE org_id = NEW.org_id
        AND phone_normalized = NEW.phone_normalized
        AND created_at > NEW.created_at;
    -- allow insert to proceed (older contact inserted, newer ones removed)
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Attach trigger on INSERT and on UPDATE if phone_normalized changes
CREATE TRIGGER trg_contacts_prevent_newer_duplicate
BEFORE INSERT ON contacts
FOR EACH ROW EXECUTE FUNCTION contacts_prevent_newer_duplicate();

-- Also apply on UPDATE if phone_normalized or created_at changes
CREATE TRIGGER trg_contacts_prevent_newer_duplicate_update
BEFORE UPDATE ON contacts
FOR EACH ROW
WHEN (OLD.phone_normalized IS DISTINCT FROM NEW.phone_normalized OR OLD.created_at IS DISTINCT FROM NEW.created_at)
EXECUTE FUNCTION contacts_prevent_newer_duplicate();
```

**Notes about the trigger:**

* If an older record exists, the insert is *rejected* (client should treat this as "duplicate exists, remove local copy").
* If the incoming record is *older* than existing records, the function deletes newer records and allows insert — this enforces "oldest wins".
* Because we have UNIQUE INDEX on `(org_id, phone_normalized)`, the trigger must run before conflict arises; the trigger deletes newer conflicting rows when appropriate.

---

# Recommended RLS (Row-Level Security) policies for Supabase

Enable RLS on tables and add safe policies. These assume `profiles` table mirrors `auth.users`. You must adjust according to your Supabase setup.

```sql
-- Enable RLS
ALTER TABLE contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;

-- Helper function to get profile role for current user
CREATE OR REPLACE FUNCTION current_profile() RETURNS profiles AS $$
  SELECT * FROM profiles WHERE id = auth.uid();
$$ LANGUAGE sql STABLE;

-- CONTACTS: SELECT — allow users in same org to see contacts
CREATE POLICY select_contacts ON contacts
  FOR SELECT
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- CONTACTS: INSERT — allow only if created_by == auth.uid() OR role=admin
CREATE POLICY insert_contacts ON contacts
  FOR INSERT
  WITH CHECK (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin')
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- CONTACTS: UPDATE — allow only if owner or admin
CREATE POLICY update_contacts ON contacts
  FOR UPDATE
  USING (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin')
  WITH CHECK (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');

-- CONTACTS: DELETE — allow only owner or admin
CREATE POLICY delete_contacts ON contacts
  FOR DELETE
  USING (created_by = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');

-- CALLS: SELECT — allow users in same org
CREATE POLICY select_calls ON calls
  FOR SELECT
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- CALLS: INSERT — allow only if user_id == auth.uid() OR admin
CREATE POLICY insert_calls ON calls
  FOR INSERT
  WITH CHECK (user_id = auth.uid() OR (SELECT role FROM profiles WHERE id = auth.uid()) = 'admin')
  USING (org_id = (SELECT org_id FROM profiles WHERE id = auth.uid()));

-- PROFILES: admin-only management (SELECT for admin)
CREATE POLICY select_profiles ON profiles
  FOR SELECT
  USING ((SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');
CREATE POLICY insert_profiles ON profiles
  FOR INSERT
  USING ((SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');
CREATE POLICY delete_profiles ON profiles
  FOR DELETE
  USING ((SELECT role FROM profiles WHERE id = auth.uid()) = 'admin');
```

> **Note:** Supabase `auth.uid()` is only available in DB functions & policies. Adjust names if your `profiles` usage differs.

---

# Client-side (Kotlin Android) implementation notes & algorithm

Below are the practical details you or your devs need to implement the client app.

## Phone normalization (critical)

* Use **Google libphonenumber** (`com.googlecode.libphonenumber:libphonenumber`) in Kotlin.
* Before any contact or call is uploaded, convert number to E.164 using user's device country (or attempt using heuristics). Store:

  * `phone_raw` = original display string
  * `phone_normalized` = e.g. `+989123456789`
* Also strip spacing/formatting and consider leading zero domestic forms. This allows treating `0912345789` and `+989123456789` as same.

## Local DB & sync queue

* Use **Room** (SQLite) to cache contacts & calls and to maintain a `sync_queue` table with `operation` (insert/update/delete), `payload`, `attempts`, `last_attempt_at`.
* Mark contacts as `dirty` when created/edited locally; push them during sync.
* After successful push, clear `dirty` flag and update `server_id` (contact.id from Supabase), `created_at`, `updated_at`.

## Sync algorithm (contacts)

1. **Normalization:** Normalize phones in local contact import step.
2. **Local changes → Push**

   * For each `dirty` contact, attempt `INSERT` or `UPDATE` to Supabase:

     * Use Supabase client upsert? Prefer explicit `INSERT` with error handling to respect trigger logic.
   * If Supabase returns duplicate error (trigger exception), mark contact as `conflict:duplicate` locally and delete local contact (per your rule) or alert user (silent delete recommended).
   * On success, store returned server `id` & timestamps.
3. **Pull remote changes**

   * Call `GET /contacts?since=<last_sync_at>` (you can implement incremental querying by `updated_at > last_sync_at`).
   * For each remote contact:

     * If local device does not have normalized phone → create local device contact (write to Contacts provider).
     * If local exists but server version older/unchanged → skip.
     * If conflict (both updated), last-writer-wins by `updated_at` or prefer server? (You specified older wins for duplicates; for edits, prefer highest `updated_at`).
4. **Cleanup duplicates**

   * After writing remote contacts to device, run a dedupe pass: For any duplicate normalized_phone on device contacts, keep the one whose `created_at` equals server's `created_at` (older wins), delete the others.

## Sync algorithm (calls)

* Periodically read `CallLog` (Android `CallLog.Calls`) and dedupe locally via `call_id` or timestamp.
* Upload new calls in batches to Supabase `calls` table; include `phone_normalized`.
* Pull remote calls for display: `GET /calls?since=<last_sync_at>` and merge into local DB for fast UI.

## Handling "don't re-upload unchanged"

* Keep `last_synced_at` and a `hash` column (or `version`) for local contact rows; only push if `last_modified > last_synced_at` or `local_version != server_version`.

## Retry & failure handling

* Use a retry queue with exponential backoff; after N attempts log error to Sync Log.
* Surface failures in Settings -> Sync Log.

## Admin user & user creation

* Admin user uses Supabase Admin API (service role key) to create auth users — BUT you said "no backend", so client-side cannot hold service role key safely. Options:

  * Use Supabase **serverless function** (Edge function) to create users — but you asked to get rid of backend. **Alternative**: use Supabase Dashboard to manually create admin at setup, and implement admin user creation by the admin using the app calling a **Postgres function** exposed and protected with RLS? That still needs a secure mechanism. The only safe way without separate backend is to use Supabase **service_role** key from a secure environment (NOT in app). So recommend:

    * Admin creates users via Supabase Dashboard OR
    * Provision a secure serverless route (Edge Function) to create users (recommended).
  * I recommend keeping admin-create-user performed from Supabase Dashboard OR using a small secure admin-only Cloud Function (not embedded in mobile app).

(If you truly want zero backend code, admin will need to create users using Supabase Dashboard — acceptable for closed teams.)

## UI specifics

* **Contacts screen**:

  * Top toolbar: search + refresh button.
  * Each row: name, phone (formatted), action icons: call, SMS, WhatsApp (open intent), three-dot → edit (if owner/admin) or view only otherwise.
  * Contact detail shows call history: each call shows date/time, user (uploader) name, and in/out icon.
* **Settings screen**:

  * Sync interval selection (+ manual sync button)
  * Toggle auto-sync on cellular / only Wi-Fi
  * Sync log (recent items)
  * Admin panel (if admin): list users (read from `profiles`), add/remove (with note re: security about admin-only server role)
  * Logout button

---

# Supabase client usage & security notes

* Use Supabase Android/Kotlin client for Auth and Postgres requests.
* For DB writes requiring admin privileges (creating users), do not store service_role key in app. Either:

  * Use Supabase Dashboard to add users manually (simplest), or
  * Deploy a tiny secure function (Cloud Function or Supabase Edge Function) that uses the service key; admin app calls that endpoint with admin JWT (this is minimal backend).
* For all normal operations (contacts & calls), RLS and policies enforce proper restrictions.

---

# Suggested improvements & trade-offs

1. **Normalization on client + server**: always normalize on client (libphonenumber) and validate server-side (store normalized_phone). Use server trigger to reject bad formats.
2. **Conflict UX**: silently deleting a user’s local contact when duplicate exists is destructive — consider notifying user with “Contact synced as duplicate and removed” or keeping a recoverable trash for 24 hours.
3. **Admin user creation**: implement a small secure server-side function for creating users (recommended) — avoids exposing service keys.
4. **Realtime updates**: enable Supabase Realtime (on `contacts` & `calls`) to push updates to clients and reduce pull frequency.
5. **Audit & logs**: store `created_by`, `updated_by`, and keep an `audit` table or `sync_changes` table for easier incremental sync and troubleshooting.
6. **Test phone normalization across locales** thoroughly — libphonenumber can still mis-parse some user inputs.
7. **Permissions & Play Store**: apps that read call logs and send messages may need to justify these permissions to Google Play; prepare a privacy policy and valid business reason for Play review.

---

# Quick checklist (developer handoff)

* [ ] Supabase project with `auth` + `profiles`, `contacts`, `calls` tables + triggers + RLS policies.
* [ ] Kotlin Android app:

  * [ ] Use libphonenumber to normalize numbers
  * [ ] Room DB (contacts, calls, sync_queue)
  * [ ] WorkManager for periodic sync
  * [ ] ContactsProvider & CallLog reading/writing
  * [ ] UI: Contacts screen + Settings screen
* [ ] Admin user created in Supabase dashboard
* [ ] Sync log UI and error handling
* [ ] Realtime (optional) with Supabase subscriptions
* [ ] Privacy text + runtime permission flows