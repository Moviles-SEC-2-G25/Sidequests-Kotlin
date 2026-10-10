-- Solo lectura. Ejecutar tras probar la pantalla y esperar a la sincronizacion.
-- Esta cuenta corresponde a la demostracion de Lex; sustituirla si se usa otra.
select
    ae.id,
    ae.occurred_at,
    ae.event_type,
    ae.quest_id as alternative_quest_id,
    ae.metadata->>'source_quest_id' as source_quest_id,
    ae.metadata->>'source_attempt_id' as source_attempt_id,
    ae.metadata->>'reason_code' as reason_code,
    ae.metadata->>'context_source' as context_source,
    ae.metadata->>'weather_condition' as weather_condition,
    ae.metadata->>'time_of_day' as time_of_day,
    ae.metadata->>'rank' as rank,
    ae.metadata->>'score' as score,
    ae.metadata->>'applied_rules' as applied_rules,
    uq.status as source_attempt_status,
    uq.abandon_reason as persisted_reason,
    exists (
        select 1 from public.analytics_events abandoned
        where abandoned.user_id = ae.user_id
          and abandoned.quest_id = ae.metadata->>'source_quest_id'
          and abandoned.event_type = 'quest_abandoned'
          and abandoned.metadata->>'attempt_id' = ae.metadata->>'source_attempt_id'
          and abandoned.metadata->>'reason_code' = ae.metadata->>'reason_code'
    ) as linked_abandonment_event
from public.analytics_events ae
left join public.user_quests uq
    on uq.id::text = ae.metadata->>'source_attempt_id'
   and uq.user_id = ae.user_id
   and uq.quest_id = ae.metadata->>'source_quest_id'
where ae.user_id = 'd854b4cc-1a0a-420a-940c-f602c1783424'::uuid
  and ae.event_type in ('quest_alternative_shown', 'quest_alternative_selected')
  and ae.metadata->>'algorithm_version' = 'lex_alternatives_v1'
order by ae.occurred_at desc, ae.id desc;
