use iroh_blobs::store::mem::MemStore;

pub fn create_send_mem_store() -> MemStore {
    MemStore::default()
}

pub fn create_recv_mem_store() -> MemStore {
    MemStore::default()
}
