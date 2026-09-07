create table if not exists public.talktoai_daily_quota (
    id text primary key,
    used integer not null default 0 check (used >= 0),
    limit_value integer not null check (limit_value > 0),
    updated_at timestamptz not null,
    reset_at timestamptz not null
);

create or replace function public.consume_talktoai_quota(
    p_id text,
    p_limit integer,
    p_updated_at timestamptz,
    p_reset_at timestamptz
)
returns table (used integer, allowed boolean)
language sql
volatile
security definer
set search_path = ''
as $$
    with consumed as (
        insert into public.talktoai_daily_quota (id, used, limit_value, updated_at, reset_at)
        values (p_id, 1, p_limit, p_updated_at, p_reset_at)
        on conflict (id) do update
        set used = talktoai_daily_quota.used + 1,
            limit_value = p_limit,
            updated_at = p_updated_at,
            reset_at = p_reset_at
        where talktoai_daily_quota.used < p_limit
        returning talktoai_daily_quota.used
    )
    select consumed.used, true as allowed from consumed
    union all
    select quota.used, false as allowed
    from public.talktoai_daily_quota quota
    where quota.id = p_id and not exists(select 1 from consumed)
    limit 1;
$$;

revoke all on table public.talktoai_daily_quota from public;
revoke all on function public.consume_talktoai_quota(text, integer, timestamptz, timestamptz) from public;
