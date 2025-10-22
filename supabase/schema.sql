-- Supabase schema for Shared Contact CRM (Kotlin Android)
-- Generated from PRD in Instructions.md

-- Ensure required extensions exist
create extension if not exists pgcrypto;

-- ---------- organizations (optional) ----------
create table if not exists organizations (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  created_at timestamptz default now()
);

-- ---------- user profiles (extends auth.users) ----------
create table if not exists profiles (
  id uuid primary key references auth.users on delete cascade,
  email text unique,
  display_name text,
  role text check (role in ('admin','user')) default 'user',
  org_id uuid references organizations(id),
  created_at timestamptz default now()
);

-- ---------- contacts ----------
create table if not exists contacts (
  id uuid primary key default gen_random_uuid(),
  org_id uuid references organizations(id) not null,
  name text not null,
  phone_raw text not null,
  phone_normalized text not null,
  created_by uuid references profiles(id) not null,
  created_at timestamptz default now(),
  updated_by uuid references profiles(id),
  updated_at timestamptz default now(),
  version int default 1
);

-- enforce uniqueness per org on normalized phone
create unique index if not exists contacts_org_phone_unique on contacts (org_id, phone_normalized);

-- ---------- calls ----------
create table if not exists calls (
  id uuid primary key default gen_random_uuid(),
  org_id uuid references organizations(id) not null,
  contact_id uuid references contacts(id),
  phone_raw text not null,
  phone_normalized text not null,
  user_id uuid references profiles(id) not null,
  direction text check (direction in ('incoming','outgoing')) not null,
  start_time timestamptz not null,
  duration_seconds int default 0,
  created_at timestamptz default now(),
  version int default 1
);

-- indexes for quick lookup
create index if not exists calls_org_contact_idx on calls (org_id, contact_id);
create index if not exists calls_org_phone_idx on calls (org_id, phone_normalized);

-- ---------- sync_changes ----------
create table if not exists sync_changes (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null,
  entity_type text check (entity_type in ('contact','call')) not null,
  entity_id uuid not null,
  change_type text check (change_type in ('insert','update','delete')) not null,
  changed_at timestamptz default now()
);

create index if not exists sync_changes_org_idx on sync_changes (org_id, changed_at);

-- ---------- Trigger & Function: Prevent newer contact insertion when older exists ----------
create or replace function contacts_prevent_newer_duplicate()
returns trigger as $$
begin
  if exists (
    select 1 from contacts
    where org_id = new.org_id
      and phone_normalized = new.phone_normalized
  ) then
    perform 1 from contacts
      where org_id = new.org_id
        and phone_normalized = new.phone_normalized
        and created_at <= new.created_at
      limit 1;
    if found then
      raise exception 'Duplicate phone exists and is older or equal; insert rejected';
    end if;
    delete from contacts
      where org_id = new.org_id
        and phone_normalized = new.phone_normalized
        and created_at > new.created_at;
  end if;
  return new;
end;
$$ language plpgsql;

-- Attach trigger on INSERT
drop trigger if exists trg_contacts_prevent_newer_duplicate on contacts;
create trigger trg_contacts_prevent_newer_duplicate
before insert on contacts
for each row execute function contacts_prevent_newer_duplicate();

-- Also apply on UPDATE if phone_normalized or created_at changes
drop trigger if exists trg_contacts_prevent_newer_duplicate_update on contacts;
create trigger trg_contacts_prevent_newer_duplicate_update
before update on contacts
for each row
when (old.phone_normalized is distinct from new.phone_normalized or old.created_at is distinct from new.created_at)
execute function contacts_prevent_newer_duplicate();

-- ---------- RLS Policies ----------
alter table contacts enable row level security;
alter table calls enable row level security;
alter table profiles enable row level security;

-- Helper: current_profile view function
create or replace function current_profile() returns profiles as $$
  select * from profiles where id = auth.uid();
$$ language sql stable;

-- CONTACTS: SELECT — users in same org
drop policy if exists select_contacts on contacts;
create policy select_contacts on contacts
  for select
  using (org_id = (select org_id from profiles where id = auth.uid()));

-- CONTACTS: INSERT — owner or admin
drop policy if exists insert_contacts on contacts;
create policy insert_contacts on contacts
  for insert
  with check (
    (created_by = auth.uid() or (select role from profiles where id = auth.uid()) = 'admin')
    and org_id = (select org_id from profiles where id = auth.uid())
  );

-- CONTACTS: UPDATE — owner or admin
drop policy if exists update_contacts on contacts;
create policy update_contacts on contacts
  for update
  using (created_by = auth.uid() or (select role from profiles where id = auth.uid()) = 'admin')
  with check (created_by = auth.uid() or (select role from profiles where id = auth.uid()) = 'admin');

-- CONTACTS: DELETE — owner or admin
drop policy if exists delete_contacts on contacts;
create policy delete_contacts on contacts
  for delete
  using (created_by = auth.uid() or (select role from profiles where id = auth.uid()) = 'admin');

-- CALLS: SELECT — users in same org
drop policy if exists select_calls on calls;
create policy select_calls on calls
  for select
  using (org_id = (select org_id from profiles where id = auth.uid()));

-- CALLS: INSERT — owner or admin
drop policy if exists insert_calls on calls;
create policy insert_calls on calls
  for insert
  with check (
    (user_id = auth.uid() or (select role from profiles where id = auth.uid()) = 'admin')
    and org_id = (select org_id from profiles where id = auth.uid())
  );

-- PROFILES: admin-only management
drop policy if exists select_profiles on profiles;
create policy select_profiles on profiles
  for select
  using ((select role from profiles where id = auth.uid()) = 'admin');

drop policy if exists insert_profiles on profiles;
create policy insert_profiles on profiles
  for insert
  with check ((select role from profiles where id = auth.uid()) = 'admin');

drop policy if exists delete_profiles on profiles;
create policy delete_profiles on profiles
  for delete
  using ((select role from profiles where id = auth.uid()) = 'admin');

-- End of schema