#!/bin/bash

install_verilator(){
  sudo apt install -y git make autoconf g++ flex libfl-dev bison  # First time prerequisites
  git clone http://git.veripool.org/git/verilator   # Only first time
  unset VERILATOR_ROOT  # For bash
  cd verilator
  git pull        # Make sure we're up-to-date
  git checkout v4.216
  autoconf        # Create ./configure script
  ./configure --prefix ~/tools
  make -j$(nproc)
  make install
  cd ..
}



install_NaxSoftware(){
  echo ${PWD}
  ls $NAXRISCV/ext/NaxSoftware
  (cd $NAXRISCV/ext/NaxSoftware  && ls && ./init.sh)
}

install_spike(){
  cd $NAXRISCV/ext/riscv-isa-sim
  mkdir build
  cd build
  ../configure --prefix=$RISCV --enable-commitlog
  make -j$(nproc)
  g++ --shared -L. -Wl,--export-dynamic -L/usr/lib/x86_64-linux-gnu  -Wl,-rpath,/lib  -o package.so spike.o  libspike_main.a  libriscv.a  libdisasm.a  libsoftfloat.a  libfesvr.a  libfdt.a -lpthread -ldl -lboost_regex -lboost_system -lpthread  -lboost_system -lboost_regex
}

install_elfio(){
  git clone https://github.com/serge1/ELFIO.git
  cd ELFIO
  sudo cp -R elfio /usr/include
  #export C_INCLUDE_PATH=${PWD}/elfio
  cd ..
}

install_gdown(){
  pip install gdown
}

install_packages(){
  sudo apt install -y zlib1g-dev libboost-dev
}

install_tools(){
  export NAXRISCV=${PWD}
  install_gdown()
  install_NaxSoftware
  install_spike
  install_elfio
  install_verilator
}