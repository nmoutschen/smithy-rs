/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

use std::sync::Arc;

use aws_sdk_sts::operation::AssumeRole;
use aws_sdk_sts::{Config, Credentials};
use aws_types::region::Region;

use super::repr;
use crate::profile::credential::repr::BaseProvider;
use crate::profile::credential::ProfileFileError;
use crate::sts;
use crate::web_identity_token::{WebIdentityTokenCredentialsProvider, WebIdentityTokenRole};
use aws_types::credential;
use aws_types::credential::{CredentialsError, ProvideCredentials};
use aws_types::os_shim_internal::Fs;
use smithy_client::DynConnector;
use std::fmt::{Debug, Formatter};

#[derive(Debug)]
pub struct AssumeRoleProvider {
    role_arn: String,
    external_id: Option<String>,
    session_name: Option<String>,
}

pub struct ClientConfiguration {
    pub(crate) core_client: aws_sdk_sts::RawClient<DynConnector>,
    pub(crate) region: Option<Region>,
}

impl AssumeRoleProvider {
    pub async fn credentials(
        &self,
        input_credentials: Credentials,
        client_config: &ClientConfiguration,
    ) -> credential::Result {
        let config = Config::builder()
            .credentials_provider(input_credentials)
            .region(client_config.region.clone())
            .build();
        let session_name = &self
            .session_name
            .as_ref()
            .cloned()
            .unwrap_or_else(|| sts::util::default_session_name("assume-role-from-profile"));
        let operation = AssumeRole::builder()
            .role_arn(&self.role_arn)
            .set_external_id(self.external_id.clone())
            .role_session_name(session_name)
            .build()
            .expect("operation is valid")
            .make_operation(&config)
            .expect("valid operation");
        let assume_role_creds = client_config
            .core_client
            .call(operation)
            .await
            .map_err(|err| CredentialsError::ProviderError(err.into()))?
            .credentials;
        sts::util::into_credentials(assume_role_creds, "AssumeRoleProvider")
    }
}

pub(crate) struct ProviderChain {
    base: Arc<dyn ProvideCredentials>,
    chain: Vec<AssumeRoleProvider>,
}

impl Debug for ProviderChain {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        // TODO: ProvideCredentials should probably mandate debug
        f.debug_struct("ProviderChain").finish()
    }
}

impl ProviderChain {
    pub fn base(&self) -> &dyn ProvideCredentials {
        self.base.as_ref()
    }

    pub fn chain(&self) -> &[AssumeRoleProvider] {
        &self.chain.as_slice()
    }
}

impl ProviderChain {
    pub fn from_repr(
        fs: Fs,
        connector: &DynConnector,
        region: Option<Region>,
        repr: repr::ProfileChain,
        factory: &named::NamedProviderFactory,
    ) -> Result<Self, ProfileFileError> {
        let base = match repr.base() {
            BaseProvider::NamedSource(name) => {
                factory
                    .provider(name)
                    .ok_or(ProfileFileError::UnknownProvider {
                        name: name.to_string(),
                    })?
            }
            BaseProvider::AccessKey(key) => Arc::new(key.clone()),
            BaseProvider::WebIdentityTokenRole {
                role_arn,
                web_identity_token_file,
                session_name,
            } => {
                let provider = WebIdentityTokenCredentialsProvider::builder()
                    .static_configuration(WebIdentityTokenRole {
                        web_identity_token_file: web_identity_token_file.into(),
                        role_arn: role_arn.to_string(),
                        session_name: session_name.map(|sess| sess.to_string()).unwrap_or_else(
                            || sts::util::default_session_name("web-identity-token-profile"),
                        ),
                    })
                    .fs(fs)
                    .connector(connector.clone())
                    .region(region)
                    .build();
                Arc::new(provider)
            }
        };
        tracing::info!(base = ?repr.base(), "first credentials will be loaded from {:?}", repr.base());
        let chain = repr
            .chain()
            .iter()
            .map(|role_arn| {
                tracing::info!(role_arn = ?role_arn, "which will be used to assume a role");
                AssumeRoleProvider {
                    role_arn: role_arn.role_arn.into(),
                    external_id: role_arn.external_id.map(|id| id.into()),
                    session_name: role_arn.session_name.map(|id| id.into()),
                }
            })
            .collect();
        Ok(ProviderChain { base, chain })
    }
}

pub mod named {
    use std::collections::HashMap;
    use std::sync::Arc;

    use aws_types::credential::ProvideCredentials;
    use std::borrow::Cow;

    pub struct NamedProviderFactory {
        providers: HashMap<Cow<'static, str>, Arc<dyn ProvideCredentials>>,
    }

    impl NamedProviderFactory {
        pub fn new(providers: HashMap<Cow<'static, str>, Arc<dyn ProvideCredentials>>) -> Self {
            Self { providers }
        }

        pub fn provider(&self, name: &str) -> Option<Arc<dyn ProvideCredentials>> {
            self.providers.get(name).cloned()
        }
    }
}

#[cfg(test)]
mod test {
    use crate::profile::credential::exec::named::NamedProviderFactory;
    use crate::profile::credential::exec::ProviderChain;
    use crate::profile::credential::repr::{BaseProvider, ProfileChain};
    use aws_sdk_sts::Region;
    use smithy_client::dvr;
    use smithy_client::erase::DynConnector;
    use std::collections::HashMap;

    fn stub_connector() -> DynConnector {
        DynConnector::new(dvr::ReplayingConnection::new(vec![]))
    }

    #[test]
    fn error_on_unknown_provider() {
        let factory = NamedProviderFactory::new(HashMap::new());
        let chain = ProviderChain::from_repr(
            Default::default(),
            &stub_connector(),
            Some(Region::new("us-east-1")),
            ProfileChain {
                base: BaseProvider::NamedSource("floozle"),
                chain: vec![],
            },
            &factory,
        );
        let err = chain.expect_err("no source by that name");
        assert!(
            format!("{}", err).contains(
                "profile referenced `floozle` provider but that provider is not supported"
            ),
            "`{}` did not match expected error",
            err
        );
    }
}