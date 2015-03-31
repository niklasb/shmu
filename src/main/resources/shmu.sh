#!/usr/bin/busybox sh

INTERVAL=0.02
WORK_DIR=/tmp/shmu$$

mkdir -p $WORK_DIR /tmp

SH=sh
NULL=/dev/null
if ! echo a >/dev/null 2>/tmp/null; then
  SH=/tmp/sh
  NULL=/tmp/null
  if ! ps fax | grep $$ | grep -v grep | grep /tmp/sh 2>$NULL; then
    # let's fake it, otherwise very weird stuff happens
    [ -e $SH ] || sed 's/\/dev\/null/\/tmp\/null/g' /bin/sh > $SH
    touch /tmp/null
    chmod +x $SH
    $SH "$0"
    exit 0
  fi
fi
echo $WORK_DIR
echo "----"

filesize() {
  wc -c $1 | cut -d' ' -f1
}

run() {
  dir=$1
  cmd="$2"
  (cat $dir/in_pipe | \
      eval "$SH -c 'echo \$\$>$dir/ppid; ($cmd)2>&1' 2>&1; echo \$?>$dir/exit_code" \
      >$dir/out_file) &
  (while [ `cat $dir/exit_code` -eq 1000000 ]; do
    have=`filesize $dir/in_file`
    already_read=`cat $dir/in_file_read`
    #echo >&2 "have=$have already_read=$already_read"
    if [ $have -gt $already_read ]; then
      offset=`expr $already_read + 1`
      #echo >&2 "New input data for $cmd ($dir) at offset $offset"
      tail -c +$offset $dir/in_file
      echo $have > $dir/in_file_read
    fi
    sleep $INTERVAL
  done) > $dir/in_pipe
}

prepare() {
  dir=$WORK_DIR/$1
  cmd="$2"
  rm -rf $dir
  mkdir -p $dir
  mknod $dir/in_pipe p
  touch $dir/in_file
  touch $dir/out_file
  echo 0 > $dir/in_file_read
  echo 0 > $dir/out_file_read
  echo 1000000 > $dir/exit_code
  echo 0 > $dir/exit_acked
  echo "$cmd" > $dir/cmd
  echo $dir
}

export top=0
echo $top > $WORK_DIR/top

kill_first_child() {
  for pid in `pgrep -P $1`; do
    kill -9 $pid
    break
  done
}

# loop 1: read from process, multiplex to stdout
(while true; do
  [ -e $WORK_DIR ] || break
  top=`cat $WORK_DIR/top`
  for i in `seq 1 $top`; do
    dir=$WORK_DIR/$i
    cmd=`cat $dir/cmd`
    have=`filesize $dir/out_file`
    already_read=`cat $dir/out_file_read`
    if [ $have -gt $already_read ]; then
      offset=`expr $already_read + 1`
      #echo >&2 "New output data from $cmd ($dir) at offset $offset"
      echo data
      echo $i
      tail -c +$offset $dir/out_file | base64
      echo ----
      echo $have > $dir/out_file_read
    fi
    exit_code=`cat $dir/exit_code`
    exit_acked=`cat $dir/exit_acked`
    if [ ! $exit_code -eq 1000000 ]; then
      if [ $exit_acked -eq 0 ]; then
        echo finish
        echo $i
        echo $exit_code
        echo 1 > $dir/exit_acked
      fi
    fi
  done
  sleep $INTERVAL
done) &
loop1_pid=$!

# loop 2: read from stdin, demultiplex, write to process
while read ty; do
  if [ "$ty" == "data" ]; then
    read target
    dir=$WORK_DIR/$target
    read inp
    #echo >&1 "Got payload for $dir"
    echo "$inp" | base64 -d >> $dir/in_file
  elif [ "$ty" == "kill" ]; then
    read target
    dir=$WORK_DIR/$target
    kill_first_child `cat $dir/ppid`
  elif [ "$ty" == "exec" ]; then
    read cmd
    top=`expr $top + 1`
    dir=`prepare $top "$cmd"`
    echo $top > $WORK_DIR/top
    run $dir "$cmd" &
  elif [ "$ty" == "exit" ]; then
    break
  fi
done

for i in `seq 1 $top`; do
  kill_first_child `cat $WORK_DIR/$i/ppid`
done
kill -9 $loop1_pid
rm -rf $WORK_DIR
