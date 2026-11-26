#!/bin/sh
# Build a private runtime config from managed files. No password appears in process arguments.
set -eu
umask 077
mode="${1:?primary, replica, sentinel or quota required}"
password_file=/run/secrets/redis_password
monitor_file=/run/secrets/redis_monitor_password
password="$(cat "$password_file")"
monitor="$(cat "$monitor_file")"
for value in "$password" "$monitor"; do
    printf '%s' "$value" | grep -Eq '^[A-Za-z0-9+/=_-]{16,128}$' || { echo 'Invalid Redis credential format' >&2; exit 2; }
done
config="$(mktemp /run/omp/redis.XXXXXX)"
printf 'bind 0.0.0.0\nprotected-mode yes\nport 6379\ndir /data\n' > "$config"
if [ "$mode" = sentinel ]; then
    sentinel_password="$(cat /run/secrets/redis_sentinel_password)"
    printf '%s' "$sentinel_password" | grep -Eq '^[A-Za-z0-9+/=_-]{16,128}$' || exit 2
    printf 'port 26379\nsentinel resolve-hostnames yes\nsentinel monitor omp-master redis 6379 2\nsentinel announce-hostnames yes\nsentinel down-after-milliseconds omp-master 5000\nsentinel failover-timeout omp-master 30000\nsentinel auth-pass omp-master %s\nrequirepass %s\n' "$password" "$sentinel_password" >> "$config"
    unset password monitor sentinel_password
    exec redis-server "$config" --sentinel
fi
printf 'appendonly yes\nappendfsync everysec\nmaxmemory 128mb\nmaxmemory-policy noeviction\n' >> "$config"
printf 'user omp_monitor on >%s -@all +ping +info +client|setname\n' "$monitor" >> "$config"
if [ "$mode" = quota ]; then
    printf 'user default off\nuser quota on >%s ~omp:quota:v1:* -@all +hello +auth +ping +eval +evalsha +script|load +hset +hmget +pexpire +time +zremrangebyscore +zscore +zcard +zadd +zrange +client|setinfo +client|setname\n' "$password" >> "$config"
else
    notifications="$(cat /run/secrets/redis_notifications_password)"
    printf '%s' "$notifications" | grep -Eq '^[A-Za-z0-9_=-]{16,128}$' || exit 2
    printf 'user notifications_svc on >%s -@all +hello +auth +ping +info +publish +subscribe +psubscribe +unsubscribe +punsubscribe +client|setname +client|setinfo &notifications:*\n' "$notifications" >> "$config"
    unset notifications
    printf 'requirepass %s\nmasterauth %s\n' "$password" "$password" >> "$config"
    if [ "$mode" = replica ]; then printf 'replicaof redis 6379\n' >> "$config"
    elif [ "$mode" != primary ]; then echo 'Unknown Redis mode' >&2; exit 2; fi
fi
unset password monitor
exec redis-server "$config"
