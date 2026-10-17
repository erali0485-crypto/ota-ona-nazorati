-- OTA-ONA NAZORATI: allow authenticated parent accounts to queue remote lock commands.
-- Device agents must only execute commands for their own enrolled device.
alter table public.pc_commands
  drop constraint if exists pc_commands_action_check;
alter table public.pc_commands
  add constraint pc_commands_action_check
  check (action in ('show_message', 'lock_workstation'));

alter table public.pc_commands
  drop constraint if exists pc_commands_status_check;
alter table public.pc_commands
  add constraint pc_commands_status_check
  check (status in ('pending', 'delivered', 'acknowledged', 'cancelled', 'failed'));

create index if not exists pc_commands_pending_lock_idx
  on public.pc_commands(device_id, created_at)
  where status = 'pending' and action = 'lock_workstation';
