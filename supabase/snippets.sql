-- Direct SQL snippet to provision Admin and Normal users WITHOUT helper functions
-- Use this when upsert_profile_by_email() doesn't exist (seed.sql not applied)
--
-- Prerequisite:
-- 1) Create the Auth users in Supabase Dashboard (Authentication -> Users)
--    - admin@ran.com
--    - user@ran.com
-- 2) Then run this DO block to ensure the organization exists and upsert profiles
--
-- NOTE: Adjust emails and organization name as needed.

do $$
declare
  org_uuid uuid;
begin
  -- Ensure organization exists (creates if missing)
  select id into org_uuid from organizations where name = 'RAN Co' limit 1;
  if org_uuid is null then
    insert into organizations(name) values ('RAN Co') returning id into org_uuid;
  end if;

  -- Upsert Admin profile (role=admin) bound to the same org
  insert into profiles (id, email, display_name, role, org_id)
  select u.id, u.email, 'Admin', 'admin', org_uuid
  from auth.users u
  where u.email = 'admin@ran.com'
  on conflict (id) do update
  set email = excluded.email,
      display_name = excluded.display_name,
      role = excluded.role,
      org_id = excluded.org_id;

  -- Upsert Normal user profile (role=user) bound to the same org
  insert into profiles (id, email, display_name, role, org_id)
  select u.id, u.email, 'User', 'user', org_uuid
  from auth.users u
  where u.email = 'user@ran.com'
  on conflict (id) do update
  set email = excluded.email,
      display_name = excluded.display_name,
      role = excluded.role,
      org_id = excluded.org_id;
end
$$ language plpgsql;

-- Verify results
select * from organizations order by created_at desc;
select id, email, display_name, role, org_id, created_at from profiles order by created_at desc;

-- Next: Retrieve org_id and set it in your app's local.properties, e.g.
-- ORG_ID=<paste org_uuid here>

-- Reference files:
-- Schema: supabase/schema.sql
-- Seed helpers (optional): supabase/seed.sql
-- User guide: supabase/USERS.md