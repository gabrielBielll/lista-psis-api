# Sincronização central com Google Agenda

O sistema usa **uma única conta Google da Deep**. Ela precisa ter acesso de
leitura aos detalhes de cada agenda de psicóloga; acesso de escrita também
funciona e é o caso atual da clínica.

## Configuração inicial

1. No Google Cloud, crie um cliente OAuth do tipo **Aplicativo da Web** e
   habilite a Google Calendar API.
2. Cadastre exatamente a URL definida em `GOOGLE_REDIRECT_URI` como URI de
   redirecionamento autorizada.
3. Preencha as variáveis de ambiente mostradas em `.env.example`. O valor de
   `GOOGLE_TOKEN_ENCRYPTION_KEY` deve ter 32 bytes codificados em Base64.
4. Faça login como administrador no gestor e acesse **Conectar Google**.
5. Escolha, no painel de administração, qual agenda Google pertence a cada
   `psicologa_id` já existente.

## Regra de disponibilidade

Somente eventos cujo título contém `[SITE-LIVRE]` são publicados. Um evento
normal que se sobreponha a ele impede a publicação do horário. Cores são
apenas visuais; não fazem parte da regra de segurança.

Uma exceção manual salva no gestor tem precedência sobre a importação:

- `disponivel: false` bloqueia um horário importado;
- `disponivel: true` abre um horário pontual extra.

O endpoint público nunca devolve título, paciente, descrição ou convidados de
eventos Google.
