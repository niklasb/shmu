## shmu, the shell multiplexer

Got a shell? One is not enought for you? Try shmu!

It upgrades any shell to a full-blown TCP server that spawns as many shells as
you want!

Useful for kernel exploitation tasks in CTFs, where you might want to interact
with several processes at once, or when you just don't want to wait for that
shell to boot up every time you run your exploit. Just connect to it once and
play with it until you kill it for good.

### Examples

    $ ./shmu ./test_shell.sh

This will start a simple shell and open a web server listing on localhost:9191
that multiplexes it.

    $ ./shmu 'ssh niklas@localhost ~/ctf/bkp/jfk/run.sh'

This will connect to localhost via SSH, spawning a QEMU session provided by a
[kernel
exploitation challenge from Boston Key Party CTF 2015](https://github.com/ctfs/write-ups-2015/tree/master/boston-key-party-2015/pwning/jfk-umass).

### Features

* Specifically designed to work even with minimalistic shells such as busybox sh
* Somewhat smart with regard to the terminal configuration used by the other
  side, such as newlines, echos etc.
