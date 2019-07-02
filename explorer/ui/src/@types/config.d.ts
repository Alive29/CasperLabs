// Declared so we can get it from the `window` object.
interface Window {
  origin: string;
  config: Config;
}

interface Config {
  auth0: Auth0Config;
}

interface Auth0Config {
  domain: string;
  clientId: string;
}

interface Account {
  // Human readable name.
  name: string;
  // Public key in PEM format.
  publicKey: string;
}

interface UserMetadata {
  accounts?: Account[];
}

interface User {
  // The User ID in Auth0.
  sub: string;
  name: string;
  email?: string;
}
