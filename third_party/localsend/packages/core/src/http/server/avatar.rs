//! The profile picture this device offers to the devices around it. This is an addition of Flick to the protocol.
//!
//! The picture is a small JPEG, set once by the app and served on `GET /api/localsend/v2/flick-avatar`. A device that
//! has no picture answers 404.

use super::common::response::{empty_body, full_body, BoxedBody};
use bytes::Bytes;
use hyper::{http, Response, StatusCode};
use std::sync::RwLock;

static AVATAR: RwLock<Bytes> = RwLock::new(Bytes::new());

/// Sets the picture offered to other devices. An empty value removes it.
pub fn set(bytes: Vec<u8>) {
    *AVATAR
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Bytes::from(bytes);
}

pub(crate) fn response() -> Response<BoxedBody> {
    let picture = AVATAR
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    if picture.is_empty() {
        let mut response = Response::new(empty_body());
        *response.status_mut() = StatusCode::NOT_FOUND;
        return response;
    }
    let mut response = Response::new(full_body(picture));
    response.headers_mut().insert(
        http::header::CONTENT_TYPE,
        http::HeaderValue::from_static("image/jpeg"),
    );
    response
}
