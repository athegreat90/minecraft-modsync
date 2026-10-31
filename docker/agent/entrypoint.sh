#!/bin/sh
set -eu
exec "/opt/modsync/bin/modsync-${MODSYNC_COMPONENT}" "$@"
