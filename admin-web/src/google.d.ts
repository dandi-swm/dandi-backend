// Google Identity Services(index.html에서 불러옴) 중 쓰는 부분만 선언한다.
type GoogleCredentialResponse = { credential: string };

interface Window {
  google?: {
    accounts: {
      id: {
        initialize(options: { client_id: string; callback: (response: GoogleCredentialResponse) => void }): void;
        renderButton(parent: HTMLElement, options: { type?: "standard"; theme?: "outline"; size?: "large"; text?: string }): void;
      };
    };
  };
}
