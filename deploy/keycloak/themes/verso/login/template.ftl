<#-- Verso login layout (ADR-0017): keycloak.v2's template with a brand panel next to the form. Only markup is
     added; Keycloak's own scripts stay as they are and the theme adds none. Texts: messages/messages_*.properties. -->
<#import "field.ftl" as field>
<#import "footer.ftl" as loginFooter>
<#import "theme-resources.ftl" as themeResourceTags>
<#macro username>
  <#assign label>
    <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
  </#assign>
  <@field.group name="username" label=label>
    <div class="${properties.kcInputGroup}">
      <div class="${properties.kcInputGroupItemClass} ${properties.kcFill}">
        <span class="${properties.kcInputClass} ${properties.kcFormReadOnlyClass}">
          <input id="kc-attempted-username" value="${auth.attemptedUsername}" readonly>
        </span>
      </div>
      <div class="${properties.kcInputGroupItemClass}">
        <button id="reset-login" class="${properties.kcFormPasswordVisibilityButtonClass} kc-login-tooltip" type="button" 
              aria-label="${msg('restartLoginTooltip')}" onclick="location.href='${url.loginRestartFlowUrl}'">
            <i class="fa-sync-alt fas" aria-hidden="true"></i>
            <span class="kc-tooltip-text">${msg("restartLoginTooltip")}</span>
        </button>
      </div>
    </div>
  </@field.group>
</#macro>

<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="color-scheme" content="light${darkMode?then(' dark', '')}">
    <meta name="viewport" content="width=device-width, initial-scale=1">

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${title!}</title>
    <#if themeResources?? && themeResources.favicons?has_content>
        <@themeResourceTags.renderFavicons themeResources.favicons url.resourcesPath />
    <#else>
        <link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />
    </#if>
    <#if themeResources?? && themeResources.stylesCommon?has_content>
        <@themeResourceTags.renderStyles themeResources.stylesCommon url.resourcesCommonPath />
    <#elseif properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if themeResources?? && themeResources.styles?has_content>
        <@themeResourceTags.renderStyles themeResources.styles url.resourcesPath />
    <#elseif properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#if darkMode>
      <script type="module" async blocking="render">
          <#outputformat "JavaScript">
          const DARK_MODE_CLASS = ${properties.kcDarkModeClass?c};
          const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

          updateDarkMode(mediaQuery.matches);
          mediaQuery.addEventListener("change", (event) => updateDarkMode(event.matches));

          function updateDarkMode(isEnabled) {
            const { classList } = document.documentElement;

            if (isEnabled) {
              classList.add(DARK_MODE_CLASS);
            } else {
              classList.remove(DARK_MODE_CLASS);
            }
          }
          </#outputformat>
      </script>
    </#if>
    <#if themeResources?? && themeResources.scripts?has_content>
        <@themeResourceTags.renderScripts themeResources.scripts url.resourcesPath "text/javascript" />
    <#elseif properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");

            if (!link) {
                return;
            }

            if (link.getAttribute("aria-disabled") === "true") {
                event.preventDefault();
                return;
            }

            const { disabledClass } = link.dataset;

            if (disabledClass) {
                link.classList.add(...disabledClass.trim().split(/\s+/));
            }

            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
             <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
    <script>
      // Workaround for https://bugzilla.mozilla.org/show_bug.cgi?id=1404468
      const isFirefox = true;
    </script>
</head>

<body id="keycloak-bg" class="${properties.kcBodyClass!} verso" data-page-id="login-${pageId}">
<div class="verso-split">
<aside class="verso-brand" aria-label="Verso">
  <div class="verso-plate">
  <div class="verso-paint verso-sky" aria-hidden="true"></div>
<#-- signature:start -->
<svg class="verso-signature" viewBox="16 81 425 169" role="img" aria-label="Verso" focusable="false">
  <defs><mask id="verso-signature-mask" maskUnits="userSpaceOnUse" x="16" y="81" width="425" height="169" fill="none" stroke="#fff" stroke-width="17.5" stroke-linecap="round" stroke-linejoin="round">
    <path class="vs-s0" d="M52 150L49 149L46 148L43 146L40 143L38 140L37 137L37 134L37 131L37 128L39 125L41 122L44 119L47 117L50 115L53 114L56 113L59 112L62 111L65 111L68 110L71 109L74 109L77 109L80 109L83 108L86 108L89 108L92 108L93 105L93 102L92 101" stroke-dasharray="99.3 99.3" stroke-dashoffset="99.3"/>
    <path class="vs-s1" d="M93 109L96 109L99 109L102 109L105 110L108 111L111 111L114 112L117 114L119 115" stroke-dasharray="27.3 27.3" stroke-dashoffset="27.3"/>
    <path class="vs-s2" d="M94 110L94 113L93 116L93 119L92 122L92 125L91 128L90 131L89 134L88 137L87 140L86 143L84 146L83 149L82 152L81 155L79 158L78 161L77 164L76 167L75 170L73 173L72 176L71 179L70 182L68 185L67 188L66 191L65 194L64 197L63 200L62 203L62 206L61 209L61 212L60 215L60 218L61 221L62 224L65 227L68 228L71 229L74 229L77 229L80 228L83 227L86 226L89 225L92 223L95 221L98 219L101 217L104 215L107 213L110 210L113 207L116 205L119 202L122 199L125 196L127 193L130 190L132 187L135 184L137 181L139 178L142 175L144 172L146 169L148 166L150 163L152 160L154 157L156 154L159 151L160 148L162 145L164 142L166 139L169 136L171 133L173 130L175 127L177 124L180 121L182 118L185 115L187 112L190 109L193 107L196 104L199 103L202 101L205 101L208 101L210 102" stroke-dasharray="328.7 328.7" stroke-dashoffset="328.7"/>
    <path class="vs-s3" d="M178 196L181 196L184 195L187 194L190 193L193 191L196 190L199 188L202 186L205 184L208 181L211 178L213 175L214 172L214 169L214 166L212 164L209 163L206 163L203 163L200 164L197 166L194 167L191 169L188 171L185 174L182 176L179 179L176 182L173 185L171 188L168 191L166 194L165 197L164 200L163 203L162 206L163 209L163 212L165 215L167 218L170 219L173 220L176 221L179 221L182 221L185 220L188 219L191 219L194 217L197 216L200 214L203 212L206 210L209 208L212 206L215 203L218 200L221 198L224 195L226 192L228 189" stroke-dasharray="211.7 211.7" stroke-dashoffset="211.7"/>
    <path class="vs-s4" d="M224 195L226 192L228 189L231 186L233 183L235 180L237 177L239 174L241 171L242 168L244 165L244 162L244 160" stroke-dasharray="41.2 41.2" stroke-dashoffset="41.2"/>
    <path class="vs-s5" d="M235 161L238 162L241 161L244 161" stroke-dasharray="9.3 9.3" stroke-dashoffset="9.3"/>
    <path class="vs-s6" d="M245 160L247 158L249 155L251 152L253 149L255 146L256 145" stroke-dasharray="18.7 18.7" stroke-dashoffset="18.7"/>
    <path class="vs-s7" d="M247 161L249 162L252 162L255 163L258 163L261 163L264 164L267 165L270 165L273 165" stroke-dasharray="26.7 26.7" stroke-dashoffset="26.7"/>
    <path class="vs-s8" d="M269 167L268 169L267 172L266 175L264 178L262 181L259 184L257 187L255 190L253 193L251 196L250 199L248 202L247 205L247 208L247 211L247 214L249 217L252 219L255 219L258 218L261 217L264 216L267 214L270 211L273 209L276 206L279 203L282 200L284 197L287 194L289 191L290 189" stroke-dasharray="110.9 110.9" stroke-dashoffset="110.9"/>
    <path class="vs-s9" d="M287 194L289 191L291 188L293 185L295 182L296 179L299 178L301 175L301 172L303 169L306 166L308 163L311 160L314 158L317 156L320 154L323 153L326 151L329 150L332 149L335 149L338 148L341 148L344 148L347 149L350 151L351 154L350 157L348 160L346 163L345 164" stroke-dasharray="100.8 100.8" stroke-dashoffset="100.8"/>
    <path class="vs-s10" d="M294 214L291 216L288 218L286 221L285 224L286 227L289 228L292 228L295 227L298 227L301 226L304 224L307 223L310 221L313 219L316 216L319 213L321 210L323 207L324 204L324 201L323 198L321 195L318 193L315 191L312 189L309 187L306 185L303 183L301 180L300 177" stroke-dasharray="103.6 103.6" stroke-dashoffset="103.6"/>
    <path class="vs-s11" d="M390 170L391 167L389 164L386 163L383 162L380 163L377 164L374 166L371 168L368 171L365 174L362 177L360 180L358 183L356 186L354 189L353 192L352 195L351 198L350 201L349 204L350 207L350 210L351 213L352 216L355 218L358 220L361 220L364 220L367 220L370 219L373 217L376 215L379 212L382 209L384 206L386 203L388 200L390 197L392 194L393 191L394 188L395 185L395 182L395 179L397 176L400 176L403 176L406 176L409 176L412 176L415 176L418 175L421 174" stroke-dasharray="178.6 178.6" stroke-dashoffset="178.6"/>
  </mask></defs>
  <path class="verso-signature-ink" d="M72.2 231.6Q61.6 231.6 57.4 227.5Q53.2 223.4 53.2 216.8Q53.2 210.6 56 202.8Q60.2 191 65.7 178.3Q71.2 165.6 76.2 153.4Q80.6 143 85 132Q89.4 121 91 113Q82.2 113 73.1 114.4Q64 115.8 56.2 118.6Q48.4 121.4 43.7 125.8Q39 130.2 39 136.4Q39 140.4 41.3 142.9Q43.6 145.4 46.4 146.8Q49.2 148.2 50.8 149.2Q52.4 150.2 52.4 150.8Q52.4 151.2 51.6 151.2Q50 151.2 45.9 149.1Q41.8 147 38.4 143.2Q35 139.4 34.8 134.4Q34.8 134 34.4 133.2Q34 132.4 34 131.8Q33.6 124.2 37.9 119Q42.2 113.8 49.6 110.5Q57 107.2 66 105.7Q75 104.2 84.2 104.2Q86 104.2 87.8 104.2Q89.6 104.2 91.4 104.4Q91.2 103 90.4 102Q90 101.2 90 100.8Q90 99.2 91.8 99.2Q93.8 99.2 95.6 102.2Q96.4 103.6 96.6 105Q108 106.2 115.6 110Q117.2 110.6 119.2 112.5Q121.2 114.4 121.2 115.6Q121.2 116.6 119.6 116.6Q118.8 116.6 117.6 116.4Q116.4 116.2 114.8 115.6Q107.6 113.4 97 113Q96.6 121.6 93.6 133.3Q90.6 145 85.8 155.8Q79.6 170.2 73.9 185.3Q68.2 200.4 67 213.2Q66.8 214 66.8 214.8Q66.8 215.6 66.8 216.4Q66.8 221.8 69 224.2Q71.2 226.6 74.8 226.6Q80.4 226.6 88.2 222.3Q96 218 103.4 212.4Q117 202.2 126.7 190.4Q136.4 178.6 145.4 164.4Q153.6 151.4 161.1 138.4Q168.6 125.4 178.4 113.8Q180.8 110.6 185 107.2Q189.2 103.8 194.3 101.4Q199.4 99 204.4 99Q206 99 207.8 99.4Q209.6 99.8 211 100.8Q212.4 101.6 211.9 102.3Q211.4 103 211 103Q210.4 103 209.8 102.9Q209.2 102.8 208.8 102.8Q204.4 102.8 198.3 108.6Q192.2 114.4 184.6 124.1Q177 133.8 168.1 145.8Q159.2 157.8 149.4 170.4Q139.2 183.6 128.3 195.9Q117.4 208.2 106.2 217Q97.6 223.8 89.1 227.7Q80.6 231.6 72.2 231.6ZM178.8 223Q169.8 223 163.2 218.8Q156.6 214.6 156.6 206Q156.6 201.4 159.4 194.8Q161.4 190.2 166.8 184.2Q172.2 178.2 179.4 172.5Q186.6 166.8 194 163.1Q201.4 159.4 207.4 159.4Q209.6 159.4 211.4 160Q214.8 161.2 216.1 163.8Q217.4 166.4 217.4 169.2Q217.4 171.8 216.6 174.2Q215.8 176.6 214.6 178.4Q211 183.8 204.3 188Q197.6 192.2 190.7 194.6Q183.8 197 179.4 197.4L179 197.4Q176.4 197.4 176.4 195.8Q176.4 194.8 178 194.8Q182.8 194.4 190.1 190.3Q197.4 186.2 203 181.8Q205.4 179.8 208.7 176.2Q212 172.6 212 169.2Q212 165.6 208.6 165.6Q206.8 165.6 204.9 166.4Q203 167.2 201.8 167.6Q196 170.4 189 176.3Q182 182.2 176.3 189.5Q170.6 196.8 168.4 204.2Q167.8 205.8 167.6 207.3Q167.4 208.8 167.4 210.2Q167.4 215 170.5 217Q173.6 219 178 219Q184.6 219 191.6 216.1Q198.6 213.2 205.2 208.6Q211.8 204 217.3 198.7Q222.8 193.4 226.6 188.4Q227.2 187.6 228 187.5Q228.8 187.4 229 187.8Q229.8 188 229.8 188.9Q229.8 189.8 229.4 190.6Q223.8 198.4 215.9 205.8Q208 213.2 198.5 218.1Q189 223 178.8 223ZM250.8 222.4Q247.4 222.4 244.8 220.9Q242.2 219.4 241.2 215.2Q240.6 213 240.6 210.8Q240.6 204.8 243.7 199.1Q246.8 193.4 251 188.5Q255.2 183.6 258.2 180.2Q259.8 178.6 261.9 176Q264 173.4 264 170.8Q264 169.2 262.4 167.7Q260.8 166.2 256.6 165.6Q254.2 165.2 252.1 164.9Q250 164.6 247.8 164.6Q242.2 172.2 236.9 179.9Q231.6 187.6 225.6 195Q224.2 196.4 223.2 196.4Q222 196.4 222 195.2Q222 194 223.2 192.8Q234 179.6 241.8 164.6Q240.8 164.6 238.8 164.5Q236.8 164.4 235.6 164.2Q234.2 163.8 233.4 163Q232.6 162.2 232.6 161.4Q232.6 160.2 233.6 160Q236.2 159.2 239.2 157.6Q242.2 156 244.8 153.4Q247.6 150.6 250 147.5Q252.4 144.4 253.8 142.8Q255.2 141.8 256.8 141.8Q257.8 141.8 258.7 142.4Q259.6 143 259.6 144.2Q259.6 145.6 258.4 147.8Q256.6 150.6 254.5 154Q252.4 157.4 250.6 160.2Q254.2 160.4 257.8 160.6Q261.4 160.8 265.2 160.8Q267.2 160.8 269.1 160.7Q271 160.6 273 160.6Q279 160.6 279 163.6Q279 165.6 276.9 168.5Q274.8 171.4 271.8 174.8Q265 182.6 260.2 191.2Q255.4 199.8 253.8 204.4Q252.2 209.2 252.2 211.6Q252.2 216.4 256 216.4Q259.4 216.4 264.3 213Q269.2 209.6 274.4 204.7Q279.6 199.8 283.8 195.1Q288 190.4 289.8 188Q290.2 187.4 291 187.4Q291.8 187.4 292.4 188.1Q293 188.8 292.4 189.8Q290.2 193.2 286 198.3Q281.8 203.4 276.6 208.4Q271.4 213.4 266.4 217Q263.8 219 259.3 220.7Q254.8 222.4 250.8 222.4ZM289.4 230Q281.4 230 281.4 225.6Q281.4 222.4 284.8 219Q287.4 216.4 290.6 214.8Q293.8 213.2 294.6 213.2Q295.6 213.2 295.7 214Q295.8 214.8 294.8 215.2Q292.8 216.4 290.3 218.5Q287.8 220.6 287.8 223Q287.8 226.2 291.6 226.2Q296.2 226.2 301.2 223.5Q306.2 220.8 310.5 216.6Q314.8 212.4 317.5 208.1Q320.2 203.8 320.2 200.6Q320.2 198 318.4 195.8Q316.6 193.6 311 190.6Q307.2 188.4 303.7 186Q300.2 183.6 298.4 180.2Q296.8 183 294.3 186.9Q291.8 190.8 288.4 195Q287.2 196.2 286 196.2Q284.8 196.2 284.8 195.2Q284.8 194.6 285.8 193.2Q291.2 186.8 293.8 181.4Q296.4 176 297.4 173.2Q298.2 167.4 302.8 162.2Q307.4 157 314 153.1Q320.6 149.2 327.5 147Q334.4 144.8 339.8 144.8Q345.2 144.8 349.4 146.8Q353.6 148.8 353.6 153.2Q353.6 155.6 352.2 158.4Q350.4 162.2 346.2 165.2Q346 165.4 345.6 165.4Q344 165.4 344 164Q344 163 345 162Q346.6 160.6 347.6 158.6Q348.6 156.6 348.6 154.8Q348.6 151.2 344.4 151.2Q340 151.2 332.9 153.6Q325.8 156 319.8 159.8Q313 164.2 308.4 169.4Q303.8 174.6 303.8 178.6Q303.8 180.6 305.1 182.3Q306.4 184 310.2 186.2Q312.2 187.4 315.8 189.3Q319.4 191.2 322 192.8Q330.2 197.8 330.2 203.4Q330.2 208 326.2 212.7Q322.2 217.4 315.9 221.3Q309.6 225.2 302.6 227.6Q295.6 230 289.4 230ZM362 222.2Q356.4 222.2 352.2 219.6Q345.2 215.4 345.2 204.4Q345.2 199.8 346.8 193.8Q348.2 188.8 351.8 182.9Q355.4 177 360.3 171.6Q365.2 166.2 371 162.8Q376.8 159.4 382.6 159.4Q385 159.4 386.8 160Q389.2 160.8 390.9 162.9Q392.6 165 392.6 168.2Q392.6 171.6 390.9 172Q389.2 172.4 389.2 171.2Q389.2 170.8 389.3 170.4Q389.4 170 389.4 169.8Q389.4 168.2 388.5 166.9Q387.6 165.6 385.2 165.6Q382 165.6 377.3 169Q372.6 172.4 367.7 178.1Q362.8 183.8 359.1 190.6Q355.4 197.4 354.2 204.2Q354 205.2 353.9 206.1Q353.8 207 353.8 208Q353.8 212.6 355.9 215.7Q358 218.8 362.6 218.8Q368.2 218.8 373.4 214.5Q378.6 210.2 382.7 204.2Q386.8 198.2 388.8 193.6Q390.4 190 391 187.4Q391.6 184.8 391.6 183Q391.6 181.4 391.3 180.2Q391 179 390.6 178.2Q390.2 177 390.2 176.4Q390.2 174.8 391.5 173.8Q392.8 172.8 394.2 172.8Q395.4 172.8 396.8 173.6Q398.4 174 401.2 174.2Q404 174.4 407.2 174.4Q410.6 174.4 414.1 174.2Q417.6 174 420.2 173.4Q420.6 173.2 421.4 173.2Q422.8 173.2 422.8 174Q422.8 174.8 421.4 175.4Q414.4 178.6 408.9 178.8Q403.4 179 399.4 179Q399.8 184 397.2 189.7Q394.6 195.4 391.8 199.8Q388.4 204.8 384 210Q379.6 215.2 374.2 218.7Q368.8 222.2 362 222.2Z" mask="url(#verso-signature-mask)"/>
</svg>
<#-- signature:end -->
  </div>
  <div class="verso-caption">${msg("versoTagline")}</div>
</aside>
<div class="${properties.kcLogin!}">
<#-- Verso (ADR-0018): the language switch is a TR / EN toggle at the top right of the page, plain links (no script). -->
<#if realm.internationalizationEnabled && locale.supported?size gt 1>
  <nav class="verso-lang" aria-label="${msg("languages")}">
  <#list locale.supported?sort_by("languageTag")?reverse as l>
    <a href="${l.url}" hreflang="${l.languageTag}" lang="${l.languageTag}" title="${l.label}"<#if l.languageTag == locale.currentLanguageTag> aria-current="true"</#if>>${l.languageTag?upper_case}</a>
  </#list>
  </nav>
</#if>
  <div class="${properties.kcLoginContainer!}">
    <header id="kc-header" class="pf-v5-c-login__header">
      <div id="kc-header-wrapper"
              class="pf-v5-c-brand">${kcSanitize(msg("loginTitleHtml",(realm.displayNameHtml!'')))?no_esc}</div>
    </header>
    <main class="${properties.kcLoginMain!}">
      <div class="${properties.kcLoginMainHeader!}">
        <h1 class="${properties.kcLoginMainTitle!}" id="kc-page-title"><#nested "header"></h1>
        <#if pageId == "login"><p class="verso-subtitle">${msg("versoLoginSubtitle")}</p></#if>
      </div>
      <div class="${properties.kcLoginMainBody!}">
        <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
            <#if displayRequiredFields>
                <div class="${properties.kcContentWrapperClass!}">
                    <div class="${properties.kcLabelWrapperClass!} subtitle">
                        <span class="${properties.kcInputHelperTextItemTextClass!}">
                          <span class="${properties.kcInputRequiredClass!}">*</span> ${msg("requiredFields")}
                        </span>
                    </div>
                </div>
            </#if>
        <#else>
            <#if displayRequiredFields>
                <div class="${properties.kcContentWrapperClass!}">
                    <div class="${properties.kcLabelWrapperClass!} subtitle">
                        <span class="${properties.kcInputHelperTextItemTextClass!}">
                          <span class="${properties.kcInputRequiredClass!}">*</span> ${msg("requiredFields")}
                        </span>
                    </div>
                    <div class="${properties.kcFormClass} ${properties.kcContentWrapperClass}">
                        <#nested "show-username">
                        <@username />
                    </div>
                </div>
            <#else>
                <div class="${properties.kcFormClass} ${properties.kcContentWrapperClass}">
                  <#nested "show-username">
                  <@username />
                </div>
            </#if>
        </#if>

        <#-- App-initiated actions should not see warning messages about the need to complete the action -->
        <#-- during login.                                                                               -->
        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="${properties.kcAlertClass!} pf-m-${(message.type = 'error')?then('danger', message.type)}">
                <div class="${properties.kcAlertIconClass!}">
                    <#if message.type = 'success'><span class="${properties.kcFeedbackSuccessIcon!}"></span></#if>
                    <#if message.type = 'warning'><span class="${properties.kcFeedbackWarningIcon!}"></span></#if>
                    <#if message.type = 'error'><span class="${properties.kcFeedbackErrorIcon!}"></span></#if>
                    <#if message.type = 'info'><span class="${properties.kcFeedbackInfoIcon!}"></span></#if>
                </div>
                <span class="${properties.kcAlertTitleClass!} kc-feedback-text">${message.summary}</span>
            </div>
        </#if>

        <#nested "form">

        <#if auth?has_content && auth.showTryAnotherWayLink()>
          <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post" novalidate="novalidate">
              <input type="hidden" name="tryAnotherWay" value="on"/>
              <a id="try-another-way" href="javascript:document.forms['kc-select-try-another-way-form'].requestSubmit()"
                  class="${properties.kcButtonSecondaryClass} ${properties.kcButtonBlockClass} ${properties.kcMarginTopClass}">
                    ${msg("doTryAnotherWay")}
              </a>
          </form>
        </#if>

        <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
          <form id="kc-switch-organization-form" action="${url.loginAction}" method="post" novalidate="novalidate">
              <input type="hidden" name="switchOrganization" value="true"/>
              <a id="switch-organization" href="javascript:document.forms['kc-switch-organization-form'].requestSubmit()"
                  class="${properties.kcButtonSecondaryClass} ${properties.kcButtonBlockClass} ${properties.kcMarginTopClass}">
                    ${msg("doSwitchOrganization")}
              </a>
          </form>
        </#if>

          <div class="${properties.kcLoginMainFooter!}">
              <#nested "socialProviders">

              <#if displayInfo>
                  <div id="kc-info" class="${properties.kcLoginMainFooterBand!} ${properties.kcFormClass}">
                      <div id="kc-info-wrapper" class="${properties.kcLoginMainFooterBandItem!}">
                          <#nested "info">
                      </div>
                  </div>
              </#if>
          </div>
      </div>

        <div class="${properties.kcLoginMainFooter!}">
            <@loginFooter.content/>
        </div>
        <div class="verso-assure"><span>${msg("versoTrustLocalTitle")}</span><span>${msg("versoTrustCitedTitle")}</span></div>
    </main>
  </div>
</div>
</div>
</body>
</html>
</#macro>
