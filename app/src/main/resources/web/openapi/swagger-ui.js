(() => {
    'use strict';

    window.addEventListener('load', () => {
        if (typeof SwaggerUIBundle !== 'function') {
            const host = document.getElementById('swagger-ui');
            host.textContent = 'Swagger UI konnte nicht aus den lokalen Assets geladen werden.';
            host.dataset.state = 'error';
            return;
        }

        window.regelsucheSwaggerUi = SwaggerUIBundle({
            url: 'openapi.json',
            dom_id: '#swagger-ui',
            deepLinking: true,
            displayOperationId: true,
            docExpansion: 'list',
            defaultModelsExpandDepth: -1,
            filter: true,
            persistAuthorization: false,
            tryItOutEnabled: false,
            validatorUrl: null,
            presets: [
                SwaggerUIBundle.presets.apis,
                SwaggerUIStandalonePreset
            ],
            layout: 'StandaloneLayout',
            onComplete: () => {
                document.getElementById('swagger-ui').dataset.state = 'ready';
            }
        });
    });
})();
