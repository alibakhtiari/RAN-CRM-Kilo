-- Seed script for Shared Contact CRM (Supabase)
-- Create or update organization and profiles for existing Auth users
--
-- Prerequisite: Create Auth users via Supabase Dashboard (Authentication -> Users)
-- Copy each user's UUID and email. Then run the SELECT upsert_profile(...) examples below.

-- Helper: ensure an organization row exists and return its id
create or replace function ensure_org(org_name text) returns uuid as $$
declare org_uuid uuid;
begin
  select id into org_uuid from organizations where name = org_name limit 1;
  if org_uuid is null then
    insert into organizations(name) values (org_name) returning id into org_uuid;
  end if;
  return org_uuid;
end;
$$ language plpgsql;

-- Helper: upsert a profile for an existing Auth user into the profiles table
create or replace function upsert_profile(
  user_uuid uuid,
  user_email text,
  display_name text,
  role_in text,
  org_name text
) returns void as $$
declare
  org_uuid uuid;
  user_exists uuid;
begin
  -- Validate role
  if role_in not in ('admin','user') then
    raise exception 'Role must be admin or user';
  end if;

  -- Ensure the auth user exists to satisfy FK (profiles.id -> auth.users.id)
  select id into user_exists from auth.users where id = user_uuid limit 1;
  if user_exists is null then
    raise exception 'Auth user id % not found. Create user in Authentication -> Users first.', user_uuid;
  end if;

  org_uuid := ensure_org(org_name);

  insert into profiles (id, email, display_name, role, org_id)
  values (user_uuid, user_email, display_name, role_in, org_uuid)
  on conflict (id) do update
  set email = excluded.email,
      display_name = excluded.display_name,
      role = excluded.role,
      org_id = excluded.org_id;
end;
$$ language plpgsql;

-- Examples: use upsert_profile_by_email after creating users in Auth (no manual UUIDs)
-- Admin user (run after creating admin@ran.com in Auth):
-- select upsert_profile_by_email('admin@ran.com', 'Admin', 'admin', 'RAN Co');

-- Normal user (run after creating user@ran.com in Auth):
-- select upsert_profile_by_email('user@ran.com', 'User', 'user', 'RAN Co');

-- Verification queries (optional)
-- Check organization
select * from organizations order by created_at desc;
-- Check profiles
select id, email, display_name, role, org_id, created_at from profiles order by created_at desc;
-- Helper: upsert a profile by email (looks up auth.users.id automatically)
create or replace function upsert_profile_by_email(
  user_email text,
  display_name text,
  role_in text,
  org_name text
) returns void as $$
declare user_uuid uuid;
begin
  if role_in not in ('admin','user') then
    raise exception 'Role must be admin or user';
  end if;

  select id into user_uuid from auth.users where email = user_email limit 1;
  if user_uuid is null then
    raise exception 'Auth user with email % not found. Create user in Authentication -> Users first.', user_email;
  end if;

  perform upsert_profile(user_uuid, user_email, display_name, role_in, org_name);
end;
$$ language plpgsql;

-- Usage examples (run after you create users in Supabase Auth -> Users)
-- Admin user
-- select upsert_profile_by_email('admin@RAN.com', 'Admin', 'admin', 'RAN Co');

-- Normal user
-- select upsert_profile_by_email('user@RAN.com', 'User', 'user', 'RAN Co');