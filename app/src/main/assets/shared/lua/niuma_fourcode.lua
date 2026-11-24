-- SPDX-License-Identifier: GPL-3.0-or-later
-- Exact source partition is validated during staging: zc begins a..y, fh begins z.
-- No dictionary writes, editor access, prediction, files, network, or input logging.
local function process(key, env)
  local ctx = env.engine.context
  if ctx:get_option('ascii_mode') or key:release() or key:ctrl() or key:alt()
      or key:super() or key:shift() then return 2 end
  local ch = key.keycode
  if ch < 97 or ch > 122 then return 2 end
  local raw = ctx.input
  -- Rollover applies to exact four-letter words and symbols alike.
  if #raw ~= 4 or not raw:match('^[a-z]+$')
      or ctx.caret_pos ~= #raw or not ctx:has_menu() then return 2 end
  local seg = ctx.composition:back()
  local first = seg and seg:get_candidate_at(0)
  -- Do not treat a suggestion for a longer code as a fully entered four-letter code.
  if not first or first.type ~= 'table' or first.start ~= 0 or first._end ~= 4 then return 2 end
  -- Global index 0, not the currently highlighted page/candidate; let the same
  -- incoming key continue through speller after the old code is committed.
  if ctx:select(0) and ctx.input ~= '' then ctx:commit() end
  return 2
end
return process
