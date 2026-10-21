#!/usr/bin/env bash
# Adds the made-up test data CLAUDE.md lists to Anu's emulator, once: a local calendar "Personal"
# (account_type LOCAL) and the contact "Maya Chen", maya.chen@example.com. Running it again changes
# nothing. It refuses any emulator that isn't anu_api36, since the others belong to another project.
#   scripts/emu-test-data.sh <serial>
set -euo pipefail

serial="${1:?usage: emu-test-data.sh <serial>}"
adb_() { adb -s "$serial" "$@"; }

avd="$(adb_ emu avd name 2>/dev/null | head -1 | tr -d '\r')"
if [[ "$avd" != "anu_api36" ]]; then
  echo "$serial is '$avd', not anu_api36: nothing added." >&2
  exit 1
fi

# The calendar, as a sync adapter for a local account, which needs no Google account.
calendars="content://com.android.calendar/calendars"
if adb_ shell content query --uri "$calendars" --projection calendar_displayName:account_type | grep -q "calendar_displayName=Personal, account_type=LOCAL"; then
  echo "Calendar \"Personal\" is already there."
else
  adb_ shell content insert \
    --uri "'$calendars?caller_is_syncadapter=true&account_name=Anu&account_type=LOCAL'" \
    --bind account_name:s:Anu --bind account_type:s:LOCAL --bind ownerAccount:s:Anu \
    --bind name:s:Personal --bind calendar_displayName:s:Personal \
    --bind calendar_color:i:-14575885 --bind calendar_access_level:i:700 \
    --bind visible:i:1 --bind sync_events:i:1 --bind calendar_timezone:s:America/New_York
  echo "Added calendar \"Personal\"."
fi

# The contact: a raw contact on the phone (no account), then its name and email.
if adb_ shell content query --uri content://com.android.contacts/data --projection data1 | grep -q "data1=maya.chen@example.com"; then
  echo "Contact \"Maya Chen\" is already there."
else
  adb_ shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
  id="$(adb_ shell content query --uri content://com.android.contacts/raw_contacts --projection _id --sort "'_id DESC'" | head -1 | sed -E 's/.*_id=([0-9]+).*/\1/' | tr -d '\r')"
  adb_ shell content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:"$id" --bind mimetype:s:vnd.android.cursor.item/name \
    --bind data1:s:"'Maya Chen'" --bind data2:s:Maya --bind data3:s:Chen
  adb_ shell content insert --uri content://com.android.contacts/data \
    --bind raw_contact_id:i:"$id" --bind mimetype:s:vnd.android.cursor.item/email_v2 \
    --bind data1:s:maya.chen@example.com --bind data2:i:1
  echo "Added contact \"Maya Chen\" (raw contact $id)."
fi
