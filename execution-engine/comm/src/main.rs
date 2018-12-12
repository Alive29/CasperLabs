extern crate clap;
extern crate execution_engine;
extern crate grpc;
extern crate protobuf;

pub mod engine_server;

use clap::{App, Arg};
use engine_server::*;
use execution_engine::engine::Engine;

fn main() {
    let matches = App::new("Execution engine server")
        .arg(Arg::with_name("socket").required(true).help("Socket file"))
        .get_matches();
    let socket = matches
        .value_of("socket")
        .expect("missing required argument");
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

    let server_builder = engine_server::new(socket, Engine::new());
    let _server = server_builder.build().expect("Start server");

    // loop idefinitely
    loop {
        std::thread::park();
    }
}
