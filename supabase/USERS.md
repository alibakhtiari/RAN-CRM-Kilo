# Supabase Users: How to add Admin and Normal Users

This app uses:
- Supabase Auth (Authentication -> Users) for identity
- A profiles table for role and org membership (admin/user + org_id)
- RLS policies that rely on profiles.id = auth.users.id

Follow these steps to add users safely without foreign key errors.

Prerequisites
- You have already run the database schema: see [schema.sql](schema.sql:1)
- You have the seed helper functions available: see [seed.sql](seed.sql:1)

1) Create Auth users (identity)
Create each user in Supabase Dashboard:
- Go to Authentication -> Users -> Add user
- Example:
  - admin@ran.com (set a password you will use in the app)
  - user@ran.com (set a password you will use in the app)

Important
- Do NOT try to directly insert into auth.users via SQL; use the Dashboard or Auth API.
- The profiles.id has a FK to auth.users.id, so the auth user must exist first.

2) Assign role and org via profiles
Open Supabase SQL Editor and run these SQL statements (adjust emails, display names, and org name):
- This uses the helper function that looks up auth.users.id by email and then upserts the matching profiles row.

-- Make sure these functions exist (they are in seed.sql):
-- ensure_org(...)
-- upsert_profile(...)
-- upsert_profile_by_email(...)

-- Admin user
select upsert_profile_by_email('admin@ran.com', 'Admin', 'admin', 'RAN Co');

-- Normal user
select upsert_profile_by_email('user@ran.com', 'User', 'user', 'RAN Co');

What this does:
- Creates the organization "RAN Co" if it doesn’t exist
- Inserts or updates a row in profiles with:
  - id = auth.users.id (looked up by email)
  - role = 'admin' or 'user'
  - org_id = the org id of "RAN Co"

Where are these functions?
- ensure_org and upsert_profile are defined in [seed.sql](seed.sql:7)
- upsert_profile_by_email is defined at the end of [seed.sql](seed.sql:71)

3) Verify and retrieve org_id
Check rows:
select * from organizations order by created_at desc;
select id, email, display_name, role, org_id, created_at from profiles order by created_at desc;

Copy the org_id UUID of "RAN Co". Put it into your Android app configuration:
- In android/local.properties:
  ORG_ID=YOUR-ORG-UUID

Rebuild the app so [SupabaseConfig](../android/app/src/main/java/com/sharedcrm/core/SupabaseConfig.kt:1) picks up the value via BuildConfig.

4) Sign in from the app
- Launch the app and accept the privacy consent
- Use email/password you created in Auth:
  - Admin: admin@ran.com
  - Normal user: user@ran.com
- The app’s Settings screen will show an Admin Panel only for admin users (RLS enforced)

5) Common errors and fixes

a) Foreign key violation on profiles.id
ERROR: insert or update on table "profiles" violates foreign key constraint "profiles_id_fkey"
Cause:
- You tried to upsert a profile with a UUID that does not exist in auth.users.
Fix:
- Always create the Auth user first in the Dashboard
- Then call:
  select upsert_profile_by_email('email@domain.com', 'Name', 'admin' or 'user', 'Org Name');

b) Role not recognized
ERROR: Role must be admin or user
Cause:
- Provided role is not "admin" or "user".
Fix:
- Only pass 'admin' or 'user'.

c) RLS not returning profiles for non-admin
- The policy [select_profiles](schema.sql:165) allows only admins to SELECT from profiles.
- Non-admin users won’t be able to list profiles, by design.

6) Changing roles or moving users to another org
- Change role:
  select upsert_profile_by_email('user@ran.com', 'User', 'admin', 'RAN Co');
- Move user to another org (e.g., "New Org"):
  select upsert_profile_by_email('user@ran.com', 'User', 'user', 'New Org');
- The function reuses or creates the organization and updates the user’s org_id.

7) Multi-org deployments (optional)
- Repeat the same steps with different organization names.
- Ensure the app’s ORG_ID is configured to the intended organization (single-org model).
- If you plan to support multi-org selection in-app, expose a switcher UI and update [SupabaseConfig](../android/app/src/main/java/com/sharedcrm/core/SupabaseConfig.kt:1)/BuildConfig at runtime via secure storage (beyond initial scope).

8) Security notes
- Do not embed the service_role key in the mobile app.
- For creating users programmatically (without Dashboard), use a secure backend/Edge Function with service role. The app should only call that backend endpoint with proper admin authentication; the mobile app itself must not hold the service key.

9) Quick checklist
- [x] Create users in Supabase Auth
- [x] Run:
  select upsert_profile_by_email('admin@ran.com', 'Admin', 'admin', 'RAN Co');
  select upsert_profile_by_email('user@ran.com', 'User', 'user', 'RAN Co');
- [x] Verify organizations and profiles
- [x] Configure ORG_ID in android/local.properties
- [x] Sign in via app, admin sees Admin Panel in [SettingsScreen](../android/app/src/main/java/com/sharedcrm/ui/settings/SettingsScreen.kt:44)