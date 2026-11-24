#!/bin/sh
# The bakery's counter (docker/compose.yml), where the customers' orders come in: each order is a menu item's name,
# pushed onto the Redis list `orders`, which every oven takes from. It runs in the Redis image, whose redis-cli it
# uses, with busybox's sh.
#
#   counter.sh          a steady trickle, about one and a half orders a second, so the graphs move; one oven keeps up
#   counter.sh rush N   a rush of N more orders, ten a second, which `demo.sh rush` starts beside the trickle

REDIS_HOST="${REDIS_HOST:-redis}"
# The items the ovens bake (app/src/main/kotlin/bakery/Oven.kt's MenuItem)
MENU="cookie muffin croissant baguette sourdough"

# A menu item, at random
item() {
  # shellcheck disable=SC2086 # one positional parameter per item
  set -- $MENU
  # shellcheck disable=SC3028 # busybox's sh, which runs this, has RANDOM
  shift $((RANDOM % $#))
  echo "$1"
}

# Pushes the given number of orders, each a random item, in one call
push() {
  n="$1"
  items=""
  while [ "$n" -gt 0 ]; do
    items="$items $(item)"
    n=$((n - 1))
  done
  # shellcheck disable=SC2086 # one argument per item
  redis-cli -h "$REDIS_HOST" RPUSH orders $items >/dev/null
}

case "${1:-trickle}" in
trickle)
  # A failed push, while Redis starts, is retried on the next order
  while :; do
    push 1 || sleep 1
    sleep 0.65
  done
  ;;
rush)
  left="${2:-500}"
  while [ "$left" -gt 0 ]; do
    batch=$((left < 10 ? left : 10))
    push "$batch" || true
    left=$((left - batch))
    sleep 1
  done
  ;;
*)
  echo "usage: counter.sh [trickle | rush <orders>]" >&2
  exit 1
  ;;
esac
