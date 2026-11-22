-- Sprint 4 / Sebastián BQ8: read-only diagnostics for a member authorized
-- to inspect the shared Supabase analytics_events table.
-- Run with Supabase SQL Editor or a read-only connection.
-- NEVER paste credentials or unredacted user-level rows in the course Wiki.
--
-- Note: metadata.source = 'rpc_viewport' is emitted by the new Kotlin branch
-- after a server-ranked recommendation enters the visible Explore viewport.
-- Historical Flutter impressions use a different event contract; do not
-- interpret zero Kotlin rows as zero activity across the whole team.

-- 1) Kotlin BQ8 event health, grouped by assigned arm.
select
    coalesce(metadata->>'variant', 'unassigned') as server_variant,
    count(*) as visible_impressions,
    count(distinct metadata->>'batch_id') as batches_with_impressions,
    count(distinct user_id) as observed_test_users,
    count(*) filter (where nullif(metadata->>'batch_id', '') is null)
        as missing_batch_id,
    count(*) filter (
        where coalesce(metadata->>'rank', '') !~ '^[1-9][0-9]*$'
    ) as missing_or_invalid_rank
from public.analytics_events
where event_type = 'recommendation_shown'
  and metadata->>'source' = 'rpc_viewport'
  and occurred_at >= now() - interval '7 days'
group by coalesce(metadata->>'variant', 'unassigned')
order by server_variant;

-- 2) Check whether batch-scoped ranks and quest IDs are unique.
-- One batch can contain fewer than 3 visible cards if a user did not scroll.
with each_batch as (
    select
        metadata->>'batch_id' as batch_id,
        count(*) as events,
        count(distinct quest_id) as distinct_quests,
        count(distinct metadata->>'rank') as distinct_ranks,
        count(distinct metadata->>'variant') as distinct_variants,
        count(*) filter (
            where metadata->>'variant' in ('control', 'diverse')
        ) as assigned_arm_events
    from public.analytics_events
    where event_type = 'recommendation_shown'
      and metadata->>'source' = 'rpc_viewport'
      and occurred_at >= now() - interval '7 days'
      and nullif(metadata->>'batch_id', '') is not null
    group by metadata->>'batch_id'
)
select
    count(*) as batches_seen,
    count(*) filter (where events <> distinct_quests) as repeated_quest_batches,
    count(*) filter (where events <> distinct_ranks) as duplicate_rank_batches,
    count(*) filter (where distinct_variants > 1) as mixed_variant_batches,
    count(*) filter (where assigned_arm_events <> events) as incomplete_arm_batches
from each_batch;

-- 3) Latest BQ8 result previously computed by the Analytics pipeline.
-- Empty result: the job may not have run; this is not evidence of zero impact.
select bq_key, computed_at, payload
from analytics.bq_results
where bq_key = 'BQ8'
order by computed_at desc
limit 1;
