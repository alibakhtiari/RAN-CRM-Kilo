// Supabase DB helper for RAN CRM
// Re-applies schema and seed, performs user upserts, verification, and ORG_ID write-back.
// See specs in README: supabase/README.md

import fs from 'fs';
import path from 'path';
import { Pool } from 'pg';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';

type CliFlags = {
  schemaOnly: boolean;
  seedOnly: boolean;
  all: boolean;
  verify: boolean;
  upsertUsers: boolean;
  org: string | undefined;
  writeOrgId: boolean;
  dbOverride: string | undefined;
  adminEmail: string | undefined;
  userEmail: string | undefined;
};

type LocalProps = {
  SUPABASE_URL?: string;
  SUPABASE_ANON_KEY?: string;
  ORG_ID?: string;
};

function parseArgs(): CliFlags {
  const argv = yargs(hideBin(process.argv))
    .option('schema-only', { type: 'boolean', default: false, desc: 'Apply schema.sql only' })
    .option('seed-only', { type: 'boolean', default: false, desc: 'Apply seed.sql only' })
    .option('all', { type: 'boolean', default: false, desc: 'Apply schema then seed' })
    .option('verify', { type: 'boolean', default: false, desc: 'Print organizations and profiles; derive org_id' })
    .option('upsert-users', { type: 'boolean', default: false, desc: 'Run upsert_profile_by_email for admin and user' })
    .option('org', { type: 'string', desc: 'Organization name for upserts and verify (e.g., "RAN Co")' })
    .option('write-org-id', { type: 'boolean', default: false, desc: 'Update ORG_ID in android/local.properties after verify' })
    .option('db', { type: 'string', desc: 'Override SUPABASE_URL connection string (postgresql://...)' })
    .option('admin-email', { type: 'string', desc: 'Override admin email (default: admin@ran.com)' })
    .option('user-email', { type: 'string', desc: 'Override user email (default: user@ran.com)' })
    .help()
    .parseSync();

  return {
    schemaOnly: argv['schema-only'] ?? false,
    seedOnly: argv['seed-only'] ?? false,
    all: argv['all'] ?? false,
    verify: argv['verify'] ?? false,
    upsertUsers: argv['upsert-users'] ?? false,
    org: argv['org'],
    writeOrgId: argv['write-org-id'] ?? false,
    dbOverride: argv['db'],
    adminEmail: argv['admin-email'],
    userEmail: argv['user-email'],
  };
}

function loadLocalProperties(): LocalProps {
  const propsPath = path.resolve(__dirname, '../android/local.properties'); // script resides in supabase/
  if (!fs.existsSync(propsPath)) {
    console.warn(`android/local.properties not found at ${propsPath}`);
    return {};
  }
  const text = fs.readFileSync(propsPath, 'utf8');
  const lines = text.split(/\r?\n/);
  const map: Record<string, string> = {};
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eqIdx = trimmed.indexOf('=');
    if (eqIdx <= 0) continue;
    const key = trimmed.slice(0, eqIdx).trim();
    const value = trimmed.slice(eqIdx + 1).trim();
    map[key] = value;
  }
  return {
    SUPABASE_URL: map['SUPABASE_URL'],
    SUPABASE_ANON_KEY: map['SUPABASE_ANON_KEY'],
    ORG_ID: map['ORG_ID'],
  };
}

function getDbPool(connectionString: string): Pool {
  return new Pool({
    connectionString,
    ssl: {
      rejectUnauthorized: false, // Supabase requires SSL; using false is typical for client scripts
    },
    max: 5,
    idleTimeoutMillis: 10_000,
    connectionTimeoutMillis: 10_000,
  });
}

function readSqlFile(relPath: string): string {
  const abs = path.resolve(__dirname, relPath);
  if (!fs.existsSync(abs)) {
    throw new Error(`SQL file not found: ${abs}`);
  }
  return fs.readFileSync(abs, 'utf8');
}

async function applySql(sectionName: string, sql: string, pool: Pool): Promise<void> {
  console.log(`\n=== ${sectionName.toUpperCase()} ===`);
  const start = Date.now();
  try {
    // Execute as a single multi-statement query to preserve plpgsql $$ blocks
    await pool.query(sql);
    const ms = Date.now() - start;
    console.log(`${sectionName} applied successfully in ${ms} ms`);
  } catch (err: any) {
    console.error(`${sectionName} failed.`);
    if (err?.message) {
      console.error(err.message);
    }
    if (err?.position) {
      console.error(`Error position: ${err.position} (character offset in the concatenated SQL)`);
    }
    throw err;
  }
}

async function upsertDefaultUsers(pool: Pool, orgName: string, adminEmail: string, userEmail: string): Promise<void> {
  console.log(`\n=== UPSERT USERS ===`);
  console.log(`Org: ${orgName}`);
  console.log(`Admin: ${adminEmail}`);
  console.log(`User: ${userEmail}`);

  const q = (email: string, display: string, role: 'admin' | 'user') =>
    `select upsert_profile_by_email('${email.replace(/'/g, "''")}', '${display}', '${role}', '${orgName.replace(/'/g, "''")}');`;

  try {
    await pool.query(q(adminEmail, 'Admin', 'admin'));
    await pool.query(q(userEmail, 'User', 'user'));
    console.log('Upsert calls executed.');
  } catch (err: any) {
    console.error('Upsert failed.');
    console.error(err?.message ?? err);
    console.error('Reminder: Create users first in Supabase Dashboard (Authentication -> Users).');
    throw err;
  }
}

type VerifyResult = {
  orgIdMatched?: string;
};

async function verifyAndSummarize(pool: Pool, orgName?: string): Promise<VerifyResult> {
  console.log(`\n=== VERIFY ===`);
  const orgs = await pool.query('select id, name, created_at from organizations order by created_at desc;');
  console.log(`Organizations (${orgs.rowCount}):`);
  for (const row of orgs.rows) {
    console.log(`- ${row.id} | ${row.name} | ${row.created_at}`);
  }

  const profiles = await pool.query(
    'select id, email, display_name, role, org_id, created_at from profiles order by created_at desc;'
  );
  console.log(`Profiles (${profiles.rowCount}):`);
  for (const row of profiles.rows) {
    console.log(`- ${row.id} | ${row.email} | ${row.display_name} | ${row.role} | org=${row.org_id} | ${row.created_at}`);
  }

  let orgIdMatched: string | undefined;
  if (orgName) {
    const match = orgs.rows.find((r: any) => `${r.name}`.toLowerCase() === orgName.toLowerCase());
    if (match) {
      orgIdMatched = match.id;
      console.log(`Matched org "${orgName}" → org_id: ${orgIdMatched}`);
    } else {
      console.warn(`No organization found with name "${orgName}".`);
    }
  }
  return { orgIdMatched };
}

function writeBackOrgId(orgId: string): void {
  console.log(`\n=== WRITE ORG_ID ===`);
  const propsPath = path.resolve(__dirname, '../android/local.properties');
  if (!fs.existsSync(propsPath)) {
    console.warn(`android/local.properties missing: ${propsPath}`);
    return;
  }
  const text = fs.readFileSync(propsPath, 'utf8');
  const lines = text.split(/\r?\n/);
  let found = false;
  const newLines = lines.map((line) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('ORG_ID=')) {
      found = true;
      return `ORG_ID=${orgId}`;
    }
    return line;
  });
  if (!found) {
    newLines.push(`ORG_ID=${orgId}`);
  }
  fs.writeFileSync(propsPath, newLines.join('\n'), 'utf8');
  console.log(`Updated ORG_ID in android/local.properties → ${orgId}`);
}

async function runDbSeed(): Promise<void> {
  const flags = parseArgs();

  // Determine operation
  const runSchema = flags.schemaOnly || flags.all || (!flags.schemaOnly && !flags.seedOnly && !flags.all);
  const runSeed = flags.seedOnly || flags.all || (!flags.schemaOnly && !flags.seedOnly && !flags.all);

  // Resolve DB connection string
  const lp = loadLocalProperties();
  const connectionString =
    flags.dbOverride?.trim() ||
    lp.SUPABASE_URL?.trim() ||
    '';

  if (!connectionString) {
    throw new Error(
      'No database connection string found. Provide --db postgresql://... or set SUPABASE_URL in android/local.properties.'
    );
  }

  console.log(`Connecting to DB: ${connectionString.replace(/:\/\/.*@/, '://****@')}`);
  const pool = getDbPool(connectionString);

  try {
    // Schema
    if (runSchema) {
      const schemaSql = readSqlFile('./schema.sql');
      await applySql('Schema', schemaSql, pool);
    } else {
      console.log('\nSkipping schema apply.');
    }

    // Seed
    if (runSeed) {
      const seedSql = readSqlFile('./seed.sql');
      await applySql('Seed', seedSql, pool);
    } else {
      console.log('\nSkipping seed apply.');
    }

    // Upsert users
    if (flags.upsertUsers) {
      const orgName = flags.org ?? 'RAN Co';
      const adminEmail = flags.adminEmail ?? 'admin@ran.com';
      const userEmail = flags.userEmail ?? 'user@ran.com';
      await upsertDefaultUsers(pool, orgName, adminEmail, userEmail);
    } else {
      console.log('\nSkipping user upserts.');
    }

    // Verify
    let verifyInfo: VerifyResult = {};
    if (flags.verify || flags.writeOrgId) {
      verifyInfo = await verifyAndSummarize(pool, flags.org);
    } else {
      console.log('\nSkipping verify.');
    }

    // Write ORG_ID back
    if (flags.writeOrgId) {
      const orgIdToWrite =
        verifyInfo.orgIdMatched ??
        lp.ORG_ID;

      if (orgIdToWrite) {
        writeBackOrgId(orgIdToWrite);
      } else {
        console.warn('No org_id available to write back. Provide --org and rerun with --verify --write-org-id.');
      }
    } else {
      console.log('\nSkipping ORG_ID write-back.');
    }

    console.log('\nAll requested operations completed.');
  } finally {
    await pool.end().catch(() => {});
  }
}

// Execute
runDbSeed().catch((err) => {
  console.error('\nHelper failed.');
  console.error(err?.message ?? err);
  process.exit(1);
});