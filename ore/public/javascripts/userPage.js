//=====> EXTERNAL CONSTANTS

var USERNAME = window.USERNAME;

//=====> HELPER FUNCTIONS

function formAsync(form, route, onSuccess) {
    form.submit(function (e) {
        e.preventDefault();
        var formData = new FormData(this);
        var spinner = $(this).find('.fa-spinner').show();
        $.ajax({
            url: route,
            data: formData,
            cache: false,
            contentType: false,
            processData: false,
            type: 'post',
            dataType: 'json',
            complete: function () {
                spinner.hide();
            },
            success: onSuccess
        });
    });
}

function setupAvatarForm() {

    $('.organization-avatar').hover(function () {
        $('.edit-avatar').fadeIn('fast');
    }, function (e) {
        if (!$(e.relatedTarget).closest("div").hasClass("edit-avatar")) {
            $('.edit-avatar').fadeOut('fast');
        }
    });

    var avatarModal = $('#modal-avatar');
    avatarModal.find('.alert').hide();

    var avatarForm = avatarModal.find('#form-avatar');
    avatarForm.find('input[name="avatar-method"]').change(function () {
        avatarForm.find('input[name="avatar-file"]').prop('disabled', $(this).val() !== 'by-file');
    });

    formAsync(avatarForm, 'organizations/' + USERNAME + '/settings/avatar', function (json) {
        if (json.hasOwnProperty('errors')) {
            var alert = avatarForm.find('.alert-danger');
            alert.find('.error').text(json['errors'][0]);
            alert.fadeIn('slow');
        } else {
            avatarModal.modal('hide');
            var success = $('.alert-success');
            success.find('.success').text('Avatar successfully updated!');
            success.fadeIn('slow');
            $('.user-avatar[title="' + USERNAME + '"]')
                .prop('src', json['avatarTemplate'].replace('{size}', '200'));
        }
    });
}


//=====> DOCUMENT READY

$(function () {
    setupAvatarForm();
});
