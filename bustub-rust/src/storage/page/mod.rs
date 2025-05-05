//pub mod page_guard;
//pub mod write_page_guard;

// src/storage_d/page_d/mod.rs
pub mod page_guard {
    include!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/storage/page/page_guard.rs"));
}

// src/storage_d/page_d/mod.rs
// pub mod write_page_guard {
//     include!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/storage_d/page_d/page_guard.rs"));
// }