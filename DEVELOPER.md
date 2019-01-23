## Building and running

__Note__ Successfully building from source requires attending to all of the prerequisites shown below. When users experience errors, it is typically related to failure to assure all prerequisites are met. Work is in progress to improve this experience.

### Prerequisites
* [OpenJDK](https://openjdk.java.net)Java Development Kit (JDK), version 10. We recommend using the OpenJDK
* [sbt](https://www.scala-sbt.org/download.html)
* [rust](https://www.rust-lang.org/tools/install)
* [protoc](https://github.com/protocolbuffers/protobuf/releases)

#### CasperLabs Development Environment on Ubuntu and Debian
<details>
  <summary>Click to expand!</summary>
  
  Java:
  ```console
  dev@dev:~$ sudo apt install openjdk-11-jdk -y
  ...

  dev@dev:~/CasperLabs$ java -version
  openjdk version "10.0.2" 2018-07-17
  OpenJDK Runtime Environment (build 10.0.2+13-Ubuntu-1ubuntu0.18.04.4)
  OpenJDK 64-Bit Server VM (build 10.0.2+13-Ubuntu-1ubuntu0.18.04.4, mixed mode)
  ```

  sbt:
  ```console
  dev@dev:~/CasperLabs$ echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
  dev@dev:~/CasperLabs$ sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
  dev@dev:~/CasperLabs$ sudo apt-get update
  dev@dev:~/CasperLabs$ sudo apt-get install sbt -y

  dev@dev:~$ sbt sbtVersion
  [info] Loading project definition from /home/dev/project
  [info] Set current project to dev (in build file:/home/dev/)
  [info] 1.2.8
  ```

  rust:
  ```console
  dev@dev:~$ curl https://sh.rustup.rs -sSf | sh
  ...
  1) Proceed with installation (default)
  2) Customize installation
  3) Cancel installation
  >1
  ...
  Rust is installed now. Great!

  To get started you need Cargo's bin directory ($HOME/.cargo/bin) in your PATH
  environment variable. Next time you log in this will be done automatically.

  To configure your current shell run source $HOME/.cargo/env

  dev@dev:~$ source $HOME/.cargo/env

  dev@dev:~$ rustup update
  info: syncing channel updates for 'stable-x86_64-unknown-linux-gnu'
  info: checking for self-updates

    stable-x86_64-unknown-linux-gnu unchanged - rustc 1.32.0 (9fda7c223 2019-01-16)

  dev@dev:~$ rustup toolchain install nightly
  info: syncing channel updates for 'nightly-x86_64-unknown-linux-gnu'
  info: latest update on 2019-01-23, rust version 1.33.0-nightly (4c2be9c97 2019-01-22)
  ...

    nightly-x86_64-unknown-linux-gnu installed - rustc 1.33.0-nightly (4c2be9c97 2019-01-22)

  dev@dev:~$ rustup target add wasm32-unknown-unknown --toolchain nightly
  info: downloading component 'rust-std' for 'wasm32-unknown-unknown'
   10.0 MiB /  10.0 MiB (100 %)   2.6 MiB/s ETA:   0 s
  info: installing component 'rust-std' for 'wasm32-unknown-unknown'
  ```

  protoc:
  ```console
  dev@dev:~$ PROTOC_VERSION=3.6.1
  dev@dev:~$ PROTOC_ZIP=protoc-$PROTOC_VERSION-linux-x86_64.zip
  dev@dev:~$ curl -OL https://github.com/google/protobuf/releases/download/v$PROTOC_VERSION/$PROTOC_ZIP
    % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                   Dload  Upload   Total   Spent    Left  Speed
  100   164    0   164    0     0    301      0 --:--:-- --:--:-- --:--:--   300
  100   619    0   619    0     0    923      0 --:--:-- --:--:-- --:--:--   923
  100 1390k  100 1390k    0     0   973k      0  0:00:01  0:00:01 --:--:-- 3029k
  dev@dev:~$ sudo unzip -o $PROTOC_ZIP -d /usr/local bin/protoc
  Archive:  protoc-3.6.1-linux-x86_64.zip
    inflating: /usr/local/bin/protoc
  dev@dev:~$ rm -f $PROTOC_ZIP

  dev@dev:~$ protoc --version
  libprotoc 3.6.1
  ```
</details>

#### CasperLabs Development Environment on macOS

TBD

#### CasperLabs Development Environment on Fedora
```
TBD
```

#### CasperLabs Development Environment on ArchLinux
You can use `pacaur` or other AUR installer instead of [`trizen`](https://github.com/trizen/trizen).
```
TBD
```

#### Building and running
Once the above prerequistes are installed, you are ready to build. 

Node and Client - Run from the root directory:
```console
dev@dev:~/CasperLabs$ sbt node/universal:stage client/universal:stage
[output] 
...
[success] Total time: 6 s, completed Jan 22, 2019, 11:21:00 PM
```

## Information for developers
Assure prerequisites shown above are met.

### Developer Quick-Start

When working in a single project, scope all `sbt` commands to that project. The most effective way is to maintain a running `sbt` instance, invoked from the project root:
```
dev@dev:~/CasperLabs$ sbt
[info] Loading settings for project casperlabs-build from plugins.sbt ...
[info] Loading project definition from /home/dev/CasperLabs/project
[info] Loading settings for project casperlabs from build.sbt ...
[info] Set current project to casperlabs (in build file:/home/dev/CasperLabs/)
[info] sbt server started at local:///home/dev/.sbt/1.0/server/0415e009bb0a13209                                                                                                                                                             cb0/sock
sbt:casperlabs> project node
[info] Set current project to node (in build file:/home/dev/CasperLabs/)
sbt:node> stage
...
[info] Done packaging.
[success] Total time: 113 s, completed Jan 22, 2019, 10:53:37 PM
```
but single-line commands work, too:
```
$ sbt "project node" clean compile test
```
or
```
$ sbt node/clean node/compile node/test
```

### Building

The build is organized into several, mostly autonomous projects. These projects may be built (and used!) on their own, or they may be combined together to form the full node package. The build process in any case is contained in and controlled by a single, top-level `build.sbt` file. This process is able to produce several different kinds of artifacts, including JAR files (for Scala) and Docker images.

The most up-to-date code is found in the `dev` branch. This brilliant, cutting-edge source is periodically merged into `master`, which branch should represent a more stable, tested version.

#### Whole-project Build

Please check the prerequistes on your machine if the code generation fails.
Then build the whole project with all submodules:
```
> sbt compile
```

#### Packaging
To publish a docker image to your local repo run:
```
> sbt node/docker:publishLocal
[... output snipped ...]
[info] Step 8/8 : ENTRYPOINT ["\/bin\/main.sh"]
[info]  ---> Running in 2ac7f835192d
[info] Removing intermediate container 2ac7f835192d
[info]  ---> 5e79e6d92528
[info] Successfully built 5e79e6d92528
[info] Tagging image 5e79e6d92528 with name: casperlabs/node
[success] Total time: 35 s, completed May 24, 2018 10:19:14 AM
```

Check the local docker repo:
```
> docker images
REPOSITORY          TAG                 IMAGE ID            CREATED             SIZE
casperlabs/node   latest              5e79e6d92528        7 minutes ago       143MB
<none>              <none>              e9b49f497dd7        47 hours ago        143MB
openjdk             8u151-jre-alpine    b1bd879ca9b3        4 months ago        82MB
```

To deploy a tarball run:
```
> sbt node/universal:packageZipTarball
```

The tarball can be found in directory `node/target/universal/`

#### Running
```
TBD
```
Now after you've done some local changes and want to test them, simply run the last command `tbd` again. It will kill the running app and start a new instance containing latest changes in a completely new forked JVM.

#### Running tests in IntelliJ

For tests of the Rholang module, make sure you've got the following JVM options set in your Run Configuration:
`-Xss240k -XX:MaxJavaStackTraceDepth=10000 -Xmx128m`

Otherwise the StackSafetySpec is going to be veeery slow and will most likely fail due to timeouts.

You can make the above options default by editing the ScalaTest Template in `Run > Edit configurations > Templates`.  

### Cross-developing for Linux (e.g. Ubuntu) on a Mac
You will need a virtual machine running the appropriate version of Linux.
1. Install [VirtualBox]( https://www.virtualbox.org/wiki/Downloads)
2. Install the Linux distribution you need (e.g. [Ubuntu](http://releases.ubuntu.com/16.04/ubuntu-16.04.4-server-amd64.iso))
3. Start VirtualBox and create a new virtual machine in the manager
4. Boot your virtual machine using the Linux distribution ISO installed in step 2.
5. Configure your Linux VM as desired. You may need to install additional tools sucah as g++, g++-multilib, make, git, etc.

For a more convenient experience, you can share a folder on your Mac with the virtual machine. To do this you will need to install the VirtualBox Guest Additions. Unfortunately there are some gotchas with this. You may need to utilize one of these [solutions](https://askubuntu.com/questions/573596/unable-to-install-guest-additions-cd-image-on-virtual-box).

## Description of subprojects

### Communication

The [comm](comm) subproject contains code for network related operations for RChain.

```
TBD
```
