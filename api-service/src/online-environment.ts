export type OnlineEnvironment = Readonly<{
  environment: 'production' | 'staging';
  apiOrigin: string; publicOrigin: string; linkScheme: string;
}>;
export function onlineEnvironment(environment = 'production'): OnlineEnvironment {
  if (environment !== 'production' && environment !== 'staging') throw new Error('Invalid online environment');
  const staging = environment === 'staging';
  return Object.freeze({ environment, apiOrigin: staging ? 'https://api-staging.pittech.app' : 'https://api.pittech.app',
    publicOrigin: staging ? 'https://staging.pittech.app' : 'https://pittech.app', linkScheme: staging ? 'pittech-staging' : 'pittech' });
}
