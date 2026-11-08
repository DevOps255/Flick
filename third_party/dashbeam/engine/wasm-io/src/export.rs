//! Browser export: iroh-blobs collection → in-memory bytes.

use anyhow::{ensure, Context};
use iroh_blobs::{api::Store, format::collection::Collection};

fn validate_path_component(component: &str) -> anyhow::Result<()> {
    ensure!(!component.is_empty(), "empty path component");
    ensure!(!component.contains('/'), "contains /");
    ensure!(!component.contains('\\'), "contains \\");
    ensure!(!component.contains(':'), "contains colon");
    ensure!(component != "..", "parent directory traversal");
    ensure!(component != ".", "current directory reference");
    ensure!(!component.contains('\0'), "contains null byte");
    Ok(())
}

fn validate_collection_path(name: &str) -> anyhow::Result<()> {
    for part in name.split('/') {
        validate_path_component(part)?;
    }
    Ok(())
}

/// Read a single-file collection back into memory.
pub async fn export_single_file_bytes(
    db: &Store,
    collection: Collection,
) -> anyhow::Result<(String, Vec<u8>)> {
    let mut iter = collection.iter();
    let (name, hash) = iter.next().context("collection is empty")?;
    ensure!(
        iter.next().is_none(),
        "expected a single-file collection"
    );

    validate_collection_path(name)?;
    let bytes = db.get_bytes(*hash).await?.to_vec();
    Ok((name.to_string(), bytes))
}

/// Read every file in a collection back into memory.
pub async fn export_collection_bytes(
    db: &Store,
    collection: Collection,
) -> anyhow::Result<Vec<(String, Vec<u8>)>> {
    let mut files = Vec::new();

    for (name, hash) in collection.iter() {
        validate_collection_path(name)?;
        let bytes = db.get_bytes(*hash).await?.to_vec();
        files.push((name.to_string(), bytes));
    }

    ensure!(!files.is_empty(), "collection is empty");
    Ok(files)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_rejects_parent_traversal() {
        assert!(validate_collection_path("../etc/passwd").is_err());
        assert!(validate_collection_path("subdir/../../etc/passwd").is_err());
    }

    #[test]
    fn validate_accepts_nested_paths() {
        assert!(validate_collection_path("my_folder/sub/file.txt").is_ok());
    }
}
